import sbt._
import Keys._

object IreneBuild extends Build {

  def cp (a: File, b: File, s: TaskStreams): Unit = {
    s.log info ("cp "+a.getPath+" "+b.getPath)
    IO copyFile (a, b)
  }

  def mkdir (tgt: File, s: TaskStreams): Unit = {
    s.log info ("mkdir "+tgt.getPath)
    IO createDirectory tgt
  }

  def zip (inp: Traversable[(File, String)], out: File, s: TaskStreams): Unit = {
    s.log info ("zip "+(inp map (_._1.getPath) mkString " ")+" "+out.getPath)
    IO zip (inp, out)
  }

  def distFromTarget (f: File, s: TaskStreams): File = {
    val tgtDir = f.getParentFile
    val projDir = tgtDir.getParentFile // REVIEW
    val distDir = new File (tgtDir, "irene-latest")

    val zEntries = 
      (((f, "irene.jar") +:
          (Array ("CHANGES.txt", "LICENSE.txt", "README.md")
                map (f => (new File (projDir, f), f))))
       map { case (item, nm) =>
          cp (item, new File (distDir, nm), s)
          (item, "irene-latest/"+(nm))
       })

    zip (zEntries, new File (tgtDir, "irene-latest.zip"), s)
    f
  }

  lazy val root = Project (id = "irene", base = file("."))
  
}
