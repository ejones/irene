package irene.util

import java.io.Reader
import java.lang.StringBuilder
import scala.collection.immutable.PagedSeq
import scala.util.parsing.input.{CharSequenceReader, PagedSeqReader}
import scala.util.parsing.input.CharArrayReader.EofCh
import scala.util.parsing.combinator.Parsers

// helpers for char sequences
trait StringParsers extends Parsers {
  type Elem = Char
  
  implicit def elemIn (elems: Elem*) : Parser[Elem] = {
    val set = elems toSet;
    elem (elems toString, set contains _)
  }

  def elemExcept (elems: Elem*) : Parser[Elem] = {
    val set = elems toSet;
    elem ("not " + set, ch => !(set contains ch))
  }

  implicit def charSeqInput (s: CharSequence) : Input = new CharSequenceReader (s)

  implicit def readerInput (rdr: Reader) : Input = 
    new PagedSeqReader (PagedSeq fromReader rdr)
  
  implicit def literal (s: CharSequence) : Parser[CharSequence] = Parser { in =>
    val source = in.source
    val offset = in.offset
    var i = 0
    var j = offset
    while (i < s.length && j < source.length && (s charAt i) == (source charAt j)) {
      i += 1
      j += 1
    }
    val found = source subSequence (offset, j)
    if (i == s.length) Success (found, in drop (j - offset))
    else Failure ("`"+s+"' expected but `"+found+"' found", in)
  }

  def chrIn = elemIn _
  def chrExcept (elems: Char*) = elemExcept ((EofCh +: elems): _*)
  def anyChr = elemExcept (EofCh)

  def spaceChar = elem ("space char", ch => (ch <= ' ' && ch != EofCh))

  def buildString (v: Any) = {
    val sb = new StringBuilder;
    def doit (v: Any) : Unit = v match {
      case ab: ~[_, _] => doit (ab._1); doit (ab._2)
      case vs: List[_] => vs foreach (doit _)
      case _ => sb append v
    }
    doit (v)
    sb toString
  }

  def rest = Parser { in => 
    val src = in source;
    val off = src offset;
    val len = src length;
    Success (src subSequence (off, len), in drop (len - off))
  }
}
