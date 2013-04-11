package irene

import collection.JavaConversions._
import collection.mutable.{LinkedHashMap, LinkedHashSet, ListBuffer, ArrayBuffer}
import java.nio.charset.Charset
import java.util.{ArrayList, HashMap, Calendar}
import java.util.logging.{Level, Logger}

import com.google.common.io.Files
import com.google.common.css
import com.google.javascript.jscomp
import com.google.javascript.jscomp.JSSourceFile
import com.google.template.soy.SoyFileSet
import com.google.template.soy.jssrc.SoyJsSrcOptions
import org.jsoup.Jsoup
import org.jsoup.nodes.{Element, DataNode, Document, Node, TextNode, Comment}
import org.jsoup.select.NodeVisitor

/**
 * Returned by compilations of different types
 */
trait ResourceError {
  
}

object ResourceCompiler {

  protected class Level_ (s: String, v: Int) extends Level (s, v)

  val SUCCESS = new Level_ ("SUCCESS", Level.WARNING.intValue)

  /** Adds functionality to java.io.File helpful for the compiler */
  class File (pathname: String) extends java.io.File (pathname) {
    lazy val (base, extension) = {
      val path = this getPath
      val idx = path lastIndexOf '.'
      if (idx == -1) (path, "")
      else (path substring (0, idx), path substring (idx, path length))
    }
    lazy val isGen = getName.contains(".min.") || getName.contains("__deps__")
    lazy val isSource = !isGen && exists
    def getSibling (pathname: String): File = new java.io.File (getParentFile, pathname)
  }
  object File {
    implicit def fromJavaFile (f: java.io.File): File = new File (f getPath)
  }

  lazy val logger = Logger getLogger classOf[ResourceCompiler].getName

  /**
   * Builds the resource tree rooted at the given file or directory. If
   * a file is given, it is built into a single unit. If a directory is given,
   * it will recurse, building files that match:
   * - *.html
   * - main.js
   * - DIR.js
   * where DIR is the parent directory of that given file.
   * 
   * @param filepath the file or directory to process
   * @param charset the encoding assumed for all input files
   * @param visitedFiles (optional) if specified, will be filled with all
   *    source files visited during compilation
   */
  def apply (filepath: String, charset: Charset,
             visitedFiles: collection.mutable.Set[File] =
                           collection.mutable.Set[File]()): Seq[ResourceError] = {

    val nonCompile = (false, Array[ResourceError](): Seq[ResourceError])

    def compile (file: File): (Boolean, Seq[ResourceError]) = {
      val startTime = math.round(System.currentTimeMillis / 1000.0) * 1000L
      val (target, mod, errs) =
        (new ResourceCompiler (startTime, charset, visitedFiles)
          .fileExtToProcessor(file.extension)(file))
      if (target.lastModified >= startTime) {
        if (errs.length == 0) {
          logger log (SUCCESS, "Compiled {0} -> {1}", Array[Object] (file.getPath, target.getPath))
        }
        (true, errs)
      } else {
        (false, errs)
      }
    }

    def visitDir (dir: File): (Boolean, Seq[ResourceError]) = {
      val targetableJs = dir.getName + ".js"
      (dir.listFiles map (File fromJavaFile _) map { file: File =>
        if (file isFile) {
          val name = file.getName
          if (!file.isGen && (file.extension == ".html"
                              || name == "main.js"
                              || name == targetableJs)) {
            compile (file)
          } else {
            nonCompile
          }
        } else if (file isDirectory) {
          visitDir (file)
        } else {
          nonCompile
        }
      }).foldLeft((false, Array[ResourceError](): Seq[ResourceError])) {
        case ((anyCompile, allErrs), (didCompile, errs)) =>
          (anyCompile || didCompile, allErrs ++ errs)
      }
    }

    val file = new File (filepath)
    val (anyComp, errs) =
      if (file isFile) {
        compile (file)
      } else if (file isDirectory) {
        visitDir (file getCanonicalFile)
      } else {
        nonCompile
      }

    if (!anyComp) {
      logger log (SUCCESS, "Everything up-to-date")
    }
    
    errs
  }

}
import ResourceCompiler._

class ResourceCompiler (startTime: Long, charset: Charset,
                        visitedFiles: collection.mutable.Set[File]) {

  /**
   * converts "foo.bar" to "foo.min.bar"
   */
  def defaultTarget (f: File) = new File (f.base + ".min" + f.extension)

  def logProcess (f: File, name: String) =
    logger log (Level.INFO, "Processing {0} with {1}", Array[Object] (f.getPath, name))
  def logVisit (f: File) = logger log (Level.FINE, "Checking {0}", Array[Object] (f.getPath))

  def pipedProc_ (cmd: Seq[String], silent: Boolean) = {
    if (!silent) {
      logger log (Level.INFO, "{0}", Array (new Object {
        override def toString = (cmd mkString " ")
      }))
    }
    var pb = new java.lang.ProcessBuilder (cmd: _*)
    pb redirectErrorStream true
    pb start
  }
  
  def pipedProc (cmd: String*) = pipedProc_ (cmd, false)
  def pipedProcSilent (cmd: String*) = pipedProc_ (cmd, true)
  
  def processLines (p: java.lang.Process): Iterator[String] = {
    val br = new java.io.BufferedReader (new java.io.InputStreamReader (p getInputStream))
    Iterator continually (br readLine) takeWhile (_ ne null)
  }

  def checkOutput (p: java.lang.Process): Either[String, Int] = {
    var output = processLines (p) mkString;
    var retcode = p waitFor;
    if (retcode == 0) {
      Left (output)
    } else {
      print (output)
      Right (retcode)
    }
  }

  var lesscPresent = true

  /**
   * Base class for generating resource processors that use JSDoc style annotations
   * for declaring dependencies (and have tools that compile multiple files as a unit)
   */
  abstract class JsDocProcessor extends (File => (File, Long, Seq[ResourceError])) {
    def fileExt: String 
    def processName: String
    def compile (deps: Iterable[File], target: File): Seq[ResourceError]
    def makeTarget (in: File) = defaultTarget (in)

    def apply (file: File) = {
      val deps = LinkedHashSet[File]()
      val errors = ArrayBuffer[ResourceError]()

      def visit (file: File): Long = {
        val (dep, modified, errs) = 
          if (file.extension != fileExt) fileExtToProcessor(file.extension)(file)
          else {
            logVisit (file)
            visitedFiles += file
            (file,
                (JsDocScraper (file, charset) flatMap (_ flatMap (_ match {
                      case DepTag (path) if !path.contains(".min.") =>
                        Some (visit (file getSibling path))
                      case _ => None
                    }))).foldLeft(file lastModified)(math.max _),
                Array[ResourceError](): Seq[ResourceError])
          }
        deps += dep
        errors ++= errs
        modified
      }

      val target = makeTarget (file)
      val modified = visit (file)
      errors ++= (
        if (modified > target.lastModified) {
          logProcess (file, processName)
          val errs = compile (deps, target)
          if (errs.length == 0) {
            target setLastModified startTime
          }
          errs
        } else {
          Array[ResourceError] ()
        })
      (target, modified, errors)
    }
  }

  lazy val nonSpacePat = java.util.regex.Pattern compile "\\S+"
  
  def undefinedCompResult = 
      (new File ("/dev/null"), 0L,
       Array (new ResourceError {}): Seq[ResourceError])

  /**
   * Factory for generating resource processors that use JSDoc style annotations
   * for declaring dependencies (and have tools that compile multiple files as a unit)
   */
  def JsDocProcessor (ext: String, name: String) 
                     (compiler: (Iterable[File], File) => Seq[ResourceError]) =
    new JsDocProcessor {
      override val fileExt = ext
      override val processName = name
      override def compile (fs: Iterable[File], tgt: File) = compiler (fs, tgt)
    }

  /**
   * Maps file extensions to functions that will take a file of that type, recurse  
   * on its dependencies, and invoke an appropriate tool to concatenate and minify it
   * all together. Outputs into an appropriately named file and returns the `File` instance.
   */
  lazy val fileExtToProcessor: Map[String, (File => (File, Long, Seq[ResourceError]))] = Map (
    ".html" -> { file =>
      logVisit (file)
      visitedFiles += file

      val doc = Jsoup parse (file, charset name)

      def getCompilableNodes(selector: String, atname: String) =
        doc select selector filter { elem =>
          !elem.hasAttr(atname) || 
                file.getSibling(elem attr atname).isSource
        }
        

      // get all the link and script tags that point to valid file locations
      // (i.e., relative paths), and inline scripts and styles, and aggregate
      // and compile them with the appropriate compilers
      val deps = 
        Array(("link[rel=stylesheet][href], style", "href", ".css", false,
                  doc head, "style", "\n", "/** @requires \"%s\" */\n"),
              ("link[rel=stylesheet/less][href]", "href", ".less", true,
                  doc head, "style", "\n", "@import \"%s\";"),
              ("script", "src", ".js", false,
                  doc body, "script", ";", "/** @requires \"%s\" */\n")) flatMap {
        case (stor, atname, ext, dblExt, injParent, injName, sep, rfmt) =>

        var aggPath = file.getPath + ".__deps__" + ext
        if (dblExt) {
          aggPath += ext
        }
        
        var aggFile = new File (aggPath)

        val depString = 
            getCompilableNodes(stor, atname) map { elem =>
              if (elem hasAttr atname) {
                visitedFiles += file getSibling (elem attr atname)
                rfmt format (elem attr atname replace ("\\", "\\\\"))
              } else {
                elem.data + sep
              }
            } mkString

        // we (re)generate an aggregate file as necessary. For references to
        // other files, we generate a line in the aggregate file 
        if ((depString.length > 0 || aggFile.exists) && file.lastModified > aggFile.lastModified) {
          if (depString.length == 0) {
            aggFile.delete
          } else {
            Files write (depString, aggFile, charset)
          }
        }

        Some (aggFile) filter (_ exists) map { f =>
            val (out, modified, errs) = fileExtToProcessor(ext)(f)
            (stor, atname, injParent, injName, out, modified, errs)
        }
      }

      val modified = deps.foldLeft(file lastModified) {
                        (acc, next) => math.max (acc, next._6) }
      val target = defaultTarget (file)

      if (modified > target.lastModified) {
        logProcess (file, "Jsoup")

        for ((stor, atname, injParent, injName, out, _, _) <- deps) {
          // inject the output into the document
          val inj = doc createElement injName
          for (s <- List ("\n", Files toString (out, charset), "\n")) {
            inj appendChild new DataNode (s, "")
          }
          val cnodes = getCompilableNodes (stor, atname)
          cnodes slice (0, cnodes.length - 1) foreach (_ remove)
          cnodes.last replaceWith inj
        }

        // update the observable $LAST_MODIFIED
        val stamp = doc createElement "script"
        stamp appendChild new DataNode ("$LAST_MODIFIED = new Date(" + startTime + ")", "")
        (doc getElementsByTag "script" match {
          case ts if ts.length > 0 => ts.first
          case _ => doc.body.children.last
        }) before stamp

        // try to compress whitespace in output (we could remove it, but that could
        // create large lines which cause problems in proxies etc.)
        doc.outputSettings prettyPrint false
        val removable = new ListBuffer[Node]()
        val replaceable = new ArrayBuffer[Node]()
        doc traverse new NodeVisitor {
          def head (n: Node, d: Int) = n match {
            case tn: TextNode if tn.isBlank => replaceable += n
            case c: Comment => removable += n
            case _ => ()
          }
          def tail (n: Node, d: Int) = ()
        }
        removable foreach (_ remove)
        for (i <- 0 until replaceable.length) {
          val n = replaceable(i)
          if (i < replaceable.length - 1 && replaceable(i + 1) == n.nextSibling) {
            n remove
          } else {
            n replaceWith new TextNode ("\n", "")
          }
        }

        Files write (doc html, target, charset)
        target setLastModified startTime
      }
      (target, modified, Array concat ((deps map (_._7.toArray)): _*))
    },
    
    ".less" -> { file =>
      logVisit (file)
      visitedFiles += file
    
      val lessc = 
        if (!lesscPresent) null
        else {
          // -M prints out all the file dependencies
          try pipedProcSilent ("lessc", file getPath, "-M", "x")
          catch {
            case e: java.io.IOException =>
              lesscPresent = false
              null
          }
        }

      if (lessc == null) {
        logger severe "lessc not found!"
        undefinedCompResult

      } else {
        checkOutput (lessc) .left flatMap { depline =>
          assert (depline startsWith "x: ")
          val depM = nonSpacePat matcher (depline substring 3)
          val deps = (Stream continually (if (depM find) depM group else null)
                             takeWhile (_ ne null)
                             map (new File (_)))

          visitedFiles ++= deps

          val target = new File (file.base + ".min.css")
          val modified = deps.foldLeft(0L) { (acc, nxt) => math max (acc, nxt lastModified) }

          if (modified > target.lastModified) {
            logProcess (file, "LESS CSS")
            checkOutput (pipedProc ("lessc", "-x", file getPath)) .left map { s =>
                Files write (s, target, charset)
                (target, modified)
            }
          } else {
            Left ((target, modified))
          }
        } match {
          case Right (errcode) =>
            undefinedCompResult
          case Left ((tgt, mod)) =>
            (tgt, mod, Array[ResourceError]())
        }

      }
    },

    ".js" -> JsDocProcessor(".js", "the Closure Compiler") { (files, target) =>
      val srcFiles = new ArrayList[JSSourceFile]()
      var hasSoy = false
      for (f <- files) {
        srcFiles add (JSSourceFile fromFile f)
        hasSoy ||= f.base.endsWith(".soy")
      }
      if (hasSoy) {
        srcFiles add (0, JSSourceFile fromInputStream (
                            "//soyutils.js",
                            classOf[SoyFileSet] getResourceAsStream "soyutils.js"))
      }

      var externs = jscomp.CommandLineRunner.getDefaultExterns.clone

      // ensure Closure Compiler doesn't rename $LAST_MODIFIED
      externs add (JSSourceFile fromCode ("//lastModified.extern.js", "var $LAST_MODIFIED"))
      

      val opts = new jscomp.CompilerOptions;
      jscomp.CompilationLevel.ADVANCED_OPTIMIZATIONS setOptionsForCompilationLevel opts
      jscomp.WarningLevel.VERBOSE setOptionsForWarningLevel opts

      // avoid the (highly problematic and controversial) property renaming
      // and turn off JSDoc warnings because of our custom stuff
      opts setRenamingPolicy (jscomp.VariableRenamingPolicy.ALL,
                              jscomp.PropertyRenamingPolicy.OFF)
      opts setWarningLevel (jscomp.DiagnosticGroups.NON_STANDARD_JSDOC,
                            jscomp.CheckLevel.OFF)

      // soy's stdlib violates ES5 strict
      if (!hasSoy) {
        opts setLanguageIn jscomp.CompilerOptions.LanguageMode.ECMASCRIPT5_STRICT
      }

      jscomp.Compiler setLoggingLevel Level.WARNING

      val compiler = new jscomp.Compiler;
      val res = compiler compile (externs, srcFiles, opts)

      val errs = res.errors map { err =>
        new ResourceError {
          override def toString = err.toString
        }
      }
    
      // add the last modified stamp and wrap everything in a closure
      val ow = Files newWriter (target, charset)
      ow write "(function(){"
      if (!hasSoy) {
        ow write "\"use strict\";"
      }
      ow write "var $LAST_MODIFIED=window.$LAST_MODIFIED||new Date(";
      ow write startTime.toString
      ow write ");"
      ow newLine;
      ow write compiler.toSource
      ow newLine;
      ow write "})()"
      ow close;

      errs
    },

    ".soy" -> new JsDocProcessor {
      override val fileExt = ".soy"
      override val processName = "Soy Templates"
      override def makeTarget (in: File) = new File (in.base + ".min.soy.js")

      override def compile (files: Iterable[File], target: File) = {
        val builder = new SoyFileSet.Builder;
        files foreach (builder add _)
        val sfs = builder build;

        Files write (
          sfs compileToJsSrc (
            new SoyJsSrcOptions {{
              setShouldGenerateJsdoc (true);
              setCodeStyle (SoyJsSrcOptions.CodeStyle.CONCAT)
            }},
            null) mkString,
          target, charset)
        Array[ResourceError]()
      }
      
    },

    ".css" -> JsDocProcessor(".css", "Closure Stylesheets") { (files, target) =>
      css.compiler.commandline.ClosureCommandLineCompiler.main(
        Array ("--allow-unrecognized-properties",
               "--allow-unrecognized-functions",
               "--output-file", target getPath)
          ++ files.map(_ getPath))
      
      Array[ResourceError]()
    }
  )
}
