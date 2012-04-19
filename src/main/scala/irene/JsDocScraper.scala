package irene

import java.io.{Reader, FileInputStream, InputStreamReader, File}
import java.nio.charset.Charset

sealed abstract class JsDocTag
case class UnknownTag (name: String) extends JsDocTag
case class DepTag (path: String) extends JsDocTag

/**
 * Extracts tags from JavaScript files without parsing the actual JavaScript. The
 * tags thus produced have only the information that we need for dependency management
 * and other compilation niceties.
 */
object JsDocScraper extends JsDocParsers {

  type Tag = JsDocTag
  override def unknownTag (name: String, _bdy: String) = UnknownTag (name)

  override lazy val tagParsers = Map (
    "requires" -> ('"' ~> rep (chrExcept ('"')) <~ '"' ^^ (s=> DepTag (s mkString))
                   | success (UnknownTag ("requires"))))

  def comment = "/*" ~> rep (chrExcept ('*') | '*' <~ not ('/')) ~> "*/"

  def stringLit (quote: Char) =
    quote ~> rep (chrExcept (quote, '\\') | '\\' ~> anyChr) <~ quote

  /**
   * Parses a whole JavaScript program for just the blocks of tags it contains
   */
  def program : Parser[List[List[JsDocTag]]] = rep (
    (chrExcept ('/', '\'', '"')
     | stringLit ('\'')
     | stringLit ('"')) ^^^ None
    | tagBlock ^^ (Some (_))
    | comment ^^^ None
  ) ^^ (_ flatten)

  def mkResult[T] (pr: ParseResult[T]) = pr match {
    case Success (ret, _) => ret
    case NoSuccess (msg, _) => scala.sys.error (msg)
  }

  /**
   * Scrapes a whole JavaScript program for the blocks of tags it contains
   */
  def apply (r: scala.util.parsing.input.Reader[Char]) = mkResult (program (r))
  
  /**
   * Scrapes a whole JavaScript program for the blocks of tags it contains
   */
  def apply (r: Reader) = mkResult (program (r))

  /**
   * Scrapes a whole JavaScript program for the blocks of tags it contains
   */
  def apply (s: String) = mkResult (program (s))

  /**
   * Scrapes a whole JavaScript program for the blocks of tags it contains
   */
  def apply (f: File, encoding: String) = 
    mkResult (program (new InputStreamReader (new FileInputStream (f), encoding)))

  /**
   * Scrapes a whole JavaScript program for the blocks of tags it contains
   */
  def apply (f: File, encoding: Charset) = 
    mkResult (program (new InputStreamReader (new FileInputStream (f), encoding)))

}
