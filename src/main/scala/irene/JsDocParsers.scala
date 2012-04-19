package irene

/**
 * Tools for parsing blocks of JSDoc tags in JavaScript files. The main entry
 * point is `tagBlock`.
 */
trait JsDocParsers extends util.StringParsers {

  /**
   * The type of tags produced by this parser
   */
  type Tag

  /**
   * Factory for unknown tags
   */
  def unknownTag (name: String, body: String) : Tag

  /**
   * Extendable list of parsers over the condensed string body of a JSDoc tag
   * (i.e., after removing the leading *'s). Extending this, in a subclass can
   * tailor the behaviour of `tagBlock`
   */
  def tagParsers : Map[String, Parser[Tag]]

  /**
   * A parser that just consumes the whole body of the tag
   */
  def simpleTagParser (cb: String => Tag) = rest ^^ (s=> cb (s toString() trim))

  // newlines are preserved in the bodies of tags in all cases with CR converted to LF
  // but U+2028 and U+2029 left intact
  def tagNL = ('\r' ~> opt ('\n') | '\n') ^^^ '\n' | chrIn ('\u2028', '\u2029')

  def optLineSpc = rep (chrIn ('\t', '\u000B', '\f', ' '))
  def tagRestOfLine = rep (chrExcept ('*', '\r', '\n', '\u2028', '\u2029')
                           | '*' <~ not ('/'))

  def tagNextLine = {
    // after a newline, there is an opt. "leader" consisting of HT, VT, FF
    // and/or space, with a star and an *optional* space, that is removed
    val opthead = '*' ~> not ('/') ~> opt (' ') ~> optLineSpc

    // we can reuse the whitespace if we're not in a leader
    tagNL ~ (optLineSpc >> { opthead | success (_) })
  }

  def tagLine = not (elem ('@') | "*/") ~> tagRestOfLine
  def tagRest : Parser[List[Char ~ List[Char] ~ List[Char]]] =
    // make sure at nextline succeeds even if tagline fails, but discards output
    (tagNextLine >> { nl => ((tagLine ^^ (new ~(nl, _))) ~ tagRest ^^ mkList
                             | success (Nil)) }
     | success (Nil))
  
  def tagBody = tagRestOfLine ~ tagRest

  def tag : Parser[Tag] = {
    val a2z = Range ('a', 'z') map (_ toChar) toList;
    val p = (('@' ~> (rep (chrIn (('_' :: a2z): _*))) <~ optLineSpc ^^ (_ mkString))
                  ~ (tagBody ^^ buildString))

    // here, we first parse the whole tag to simplify the processing of the *'s in the
    // columns. then invoke a "subparser" on the string thus returned, and re-align
    // the output with the original parser
    Parser { in => p (in) match {
      case Success (nm ~ body, next) => 
        tagParsers.getOrElse(nm, success (unknownTag (nm, body))) (body) match {
          case Success (ret, _) => Success (ret, next)
          case Failure (msg, _) => Failure ("In tag \""+nm+"\":\n"+msg, in)
          case Error (msg, _) => Error ("In tag \""+nm+"\":\n"+msg, in)
        }
      case Failure (msg, next) => Failure (msg, next)
      case Error (msg, next) => Error (msg, next)
    } }
  }

  /**
   * Parses a whole JsDoc comment and returns all the tags it contains
   */
  def tagBlock : Parser[List[Tag]] =
    "/**" ~> optLineSpc ~> opt (tagLine ~> tagRest) ~> tag.* <~ "*/" 

}
