import sbt._
import Keys._

object IreneBuild extends Build {

  def cp (a: File, b: File, s: TaskStreams): Unit = {
    s.log info ("cp "+a.getPath+" "+b.getPath)
    IO copyFile (a, b)
  }

  def zip (inp: Traversable[(File, String)], out: File, s: TaskStreams): Unit = {
    s.log info ("zip "+(inp map (_._1.getPath) mkString " ")+" "+out.getPath)
    IO zip (inp, out)
  }

  def distFromTarget (f: File, s: TaskStreams): File = {
    var tgtDir = f.getParentFile
    var projDir = tgtDir.getParentFile // REVIEW
    cp (f, new File (tgtDir, "irene-latest.jar"), s)
    zip (
      (f, "irene-latest/irene.jar") +:
        (Array ("CHANGES.txt", "LICENSE.txt", "README.md") map { nm: String =>
          (new File (projDir, nm), "irene-latest/"+nm)
         }),
      new File (tgtDir, "irene-latest.zip"),
      s)
    f
  }

  lazy val root = Project (id = "irene", base = file("."))
  
}
