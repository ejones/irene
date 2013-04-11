import AssemblyKeys._

name := "irene"

version := String.format("%tF", new java.util.Date)

scalaVersion := "2.9.1"

assemblySettings

assembly <<= (assembly, streams) map distFromTarget

mergeStrategy in assembly ~= { old =>
  {
    // there is a conflict between vanilla cglib and one provided by org.sonatype.sisu.inject
    case PathList ("net", "sf", "cglib", _*) => MergeStrategy.first
    case x => old (x)
  }
}

jarName in assembly <<= (jarName in assembly, name, version) map { (_, n, v) => n + "-" + v + ".jar" }

libraryDependencies ++= Seq(
  "com.google.template" % "soy" % "2011-14-10",
  "com.google.closure-stylesheets" % "closure-stylesheets" % "2011-12-30",
  "com.google.javascript" % "closure-compiler" % "r1810",
  "org.jsoup" % "jsoup" % "1.6.2")

resolvers ++= Seq(
  "codedance on Github" at "https://github.com/codedance/maven-repository/raw/master",
  "ejones/maven on Github" at "https://github.com/ejones/maven/raw/master")
