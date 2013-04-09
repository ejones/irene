package irene

import annotation.tailrec
import collection.JavaConversions._
import java.io.{File, InputStreamReader}
import java.lang.{StringBuilder, Thread}
import java.nio.charset.Charset
import java.util.logging.Logger

import com.google.common.io.{CharStreams, Files}

class Program

object Program {

  lazy val defaultCharset = Charset forName "UTF-8"
  lazy val logger = Logger getLogger classOf[Program].getName

  def main (args: Array[String]): Unit = {

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
