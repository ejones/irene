package irene

import annotation.tailrec
import collection.JavaConversions._
import java.io.{File, InputStreamReader}
import java.lang.{StringBuilder, Thread, System}
import java.nio.charset.Charset
import java.util.logging.{Logger, LogRecord, Level, ConsoleHandler}

import com.google.common.io.{CharStreams, Files}

class Program

object Program {

  lazy val defaultCharset = Charset forName "UTF-8"
  lazy val logger = Logger getLogger classOf[Program].getName

  def main (cargs: Array[String]): Unit = {
    val (verbose, args) = 
      if (cargs(0) == "verbose") {
        (true, cargs slice (1, Int.MaxValue))
      } else {
        (false, cargs)
      }
  
    // REVIEW - set up root logger with our custom formatter, level
    val rootLogger = Logger getLogger ""
    val cHandlers = rootLogger getHandlers() filter (_.isInstanceOf[ConsoleHandler])
    
    cHandlers foreach { h =>
      h setFormatter (new Formatter (System.console != null))
      h setLevel (
        if (verbose) Level.FINE
        // HACK
        else if (Array ("develop", "create") contains args(0)) Level.INFO
        else Level.WARNING)
    }

    processArgs (args)
  }

  def processArgs (args: Array[String]) {
    def pathArgAtIndex (idx: Int) = if (args.length > idx) args(idx) else "."

    if (args.length <= 1) {
      // TODO: specify input encoding at cmd line
      ResourceCompiler (pathArgAtIndex(0), defaultCharset)

    } else args(0) match {
      // use a template to generate a project
      case "create" => {
        val (templName, name) = (if (args.length > 2) (args(1), args(2))
                                 else ("basic", args(1)))
        logger info ("Creating project \"" + name + "\" with template " + templName)

        var content = new StringBuilder()
        var file: File = null
        for (line <- CharStreams readLines (
                new InputStreamReader (
                    classOf[Program] getResourceAsStream (templName + ".template")))) {
          // new file boundary
          if (line startsWith "%%FILE") {
            if (file != null) {
              Files write (content, file, defaultCharset)
            }
            val fname = line substring "%%FILE".length trim() replace ("%NAME%", name)
            logger info ("Generating " + fname)
            file = new File (fname)
            content = new StringBuilder()
            Files createParentDirs file
          } else {
            content append (line replace ("%NAME%", name)) append '\n'
          }
        }
        if (file != null) {
          Files write (content, file, defaultCharset)
        }
      }

      // monitor compilable files and reload
      case "develop" => {
        val visitedFiles = collection.mutable.Set[ResourceCompiler.File]()
        while (true) {
          val startTime = System.currentTimeMillis

          val errs = ResourceCompiler (pathArgAtIndex(1), defaultCharset, visitedFiles)

          if (errs.length > 0) {
            logger severe (errs.length + " error(s) found! See console for details")
          }
          logger info ("Waiting for changes...")
          @tailrec def monitor: Unit = {
            Thread sleep 2000
            visitedFiles find (_.lastModified > startTime) match {
              case Some(f) => logger info (f.getPath + " modified...")
              case _ => monitor
            }
          }
          monitor
        }
      }

    }
  }
}

class Formatter (colorize: Boolean) extends java.util.logging.Formatter {
  override def format (record: LogRecord): String = {
    val lvl = record.getLevel
    val (ansi, name) =
      lvl match {
           case x if (x eq ResourceCompiler.SUCCESS) => ("2m", None) // green
           case Level.SEVERE => ("1;1m", None) // RED
           case Level.WARNING => ("3;1m", None) // YELLOW
           case _ => ("6m", Some ("INFO")) // cyan
      }

    ("[" +
      (if (colorize) "\u001b[3" + ansi else "") +
      (name getOrElse (lvl toString) toLowerCase) +
      (if (colorize) "\u001b[0m" else "") +
      "] " + formatMessage (record) + "\n")
  }
}
