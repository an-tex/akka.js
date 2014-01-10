package org.scalajs.actors

import scala.annotation.tailrec
import scala.collection.immutable
import java.lang.{ StringBuilder => JStringBuilder }

object ActorPath {
  /**
   * Parse string as actor path; throws java.net.MalformedURLException if unable to do so.
   */
  /*def fromString(s: String): ActorPath = s match {
    case ActorPathExtractor(addr, elems) => RootActorPath(addr) / elems
    case _ => throw new /*MalformedURLException*/IllegalArgumentException("cannot parse as ActorPath: " + s)
  }*/

  /**
   * This Regular Expression is used to validate a path element (Actor Name).
   * Since Actors form a tree, it is addressable using an URL, therefore an Actor Name has to conform to:
   * http://www.ietf.org/rfc/rfc2396.txt
   */
  val ElementRegex =
    """(?:[-\w:@&=+,.!~*'_;]|%\p{XDigit}{2})(?:[-\w:@&=+,.!~*'$_;]|%\p{XDigit}{2})*""".r

  private[actors] final val emptyActorPath: immutable.Iterable[String] = List("")
}

/**
 * Actor path is a unique path to an actor that shows the creation path
 * up through the actor tree to the root actor.
 *
 * ActorPath defines a natural ordering (so that ActorRefs can be put into
 * collections with this requirement); this ordering is intended to be as fast
 * as possible, which owing to the bottom-up recursive nature of ActorPath
 * is sorted by path elements FROM RIGHT TO LEFT, where RootActorPath >
 * ChildActorPath in case the number of elements is different.
 *
 * Two actor paths are compared equal when they have the same name and parent
 * elements, including the root address information. That does not necessarily
 * mean that they point to the same incarnation of the actor if the actor is
 * re-created with the same path. In other words, in contrast to how actor
 * references are compared the unique id of the actor is not taken into account
 * when comparing actor paths.
 */
sealed trait ActorPath extends Comparable[ActorPath] {
  /**
   * The Address under which this path can be reached; walks up the tree to
   * the RootActorPath.
   */
  def address: Address

  /**
   * The name of the actor that this path refers to.
   */
  def name: String

  /**
   * The path for the parent actor.
   */
  def parent: ActorPath

  /**
   * Create a new child actor path.
   */
  def /(child: String): ActorPath

  /**
   * Recursively create a descendant’s path by appending all child names.
   */
  def /(child: Iterable[String]): ActorPath =
    (this /: child)((path, elem) => if (elem.isEmpty) path else path / elem)

  /**
   * Sequence of names for this path from root to this. Performance implication: has to allocate a list.
   */
  def elements: immutable.Iterable[String]

  /**
   * Walk up the tree to obtain and return the RootActorPath.
   */
  def root: RootActorPath

  def toSerializationFormat: String

  def toStringWithAddress(addr: Address): String

  def toSerializationFormatWithAddress(addr: Address): String
}

/**
 * Root of the hierarchy of ActorPaths. There is exactly root per ActorSystem
 * and node (for remote-enabled or clustered systems).
 */
final case class RootActorPath(address: Address, name: String = "/") extends ActorPath {

  override def parent: ActorPath = this

  override def root: RootActorPath = this

  override def /(child: String): ActorPath =
    new ChildActorPath(this, child)

  override def elements: immutable.Iterable[String] = ActorPath.emptyActorPath

  override val toString: String = address + name

  override val toSerializationFormat: String = toString

  override def toStringWithAddress(addr: Address): String =
    /*if (address.host.isDefined) address + name
    else*/ addr + name

  override def toSerializationFormatWithAddress(addr: Address): String = toStringWithAddress(addr)

  override def compareTo(other: ActorPath): Int = other match {
    case r: RootActorPath  ⇒ toString compareTo r.toString // FIXME make this cheaper by comparing address and name in isolation
    case c: ChildActorPath ⇒ 1
  }
}

final case class ChildActorPath private[actors] (
    val parent: ActorPath, val name: String) extends ActorPath {

  if (name.indexOf('/') != -1)
    throw new IllegalArgumentException(
        "/ is a path separator and is not legal in ActorPath names: [%s]" format name)
  if (name.indexOf('#') != -1)
    throw new IllegalArgumentException(
        "# is a fragment separator and is not legal in ActorPath names: [%s]" format name)

  override def address: Address = root.address

  override def /(child: String): ActorPath =
    new ChildActorPath(this, child)

  override def elements: immutable.Iterable[String] = {
    @tailrec
    def rec(p: ActorPath, acc: List[String]): immutable.Iterable[String] = p match {
      case r: RootActorPath => acc
      case _                => rec(p.parent, p.name :: acc)
    }
    rec(this, Nil)
  }

  override def root: RootActorPath = {
    @tailrec
    def rec(p: ActorPath): RootActorPath = p match {
      case r: RootActorPath => r
      case _                => rec(p.parent)
    }
    rec(this)
  }

  override def toString: String = {
    val length = toStringLength
    buildToString(new JStringBuilder(length), length, 0, _.toString).toString
  }

  override def toSerializationFormat: String = {
    val length = toStringLength
    val sb = buildToString(new JStringBuilder(length + 12), length, 0, _.toString)
    sb.toString
  }

  private def toStringLength: Int = toStringOffset + name.length

  private val toStringOffset: Int = parent match {
    case r: RootActorPath  ⇒ r.address.toString.length + r.name.length
    case c: ChildActorPath ⇒ c.toStringLength + 1
  }

  override def toStringWithAddress(addr: Address): String = {
    val diff = addressStringLengthDiff(addr)
    val length = toStringLength + diff
    buildToString(new JStringBuilder(length), length, diff, _.toStringWithAddress(addr)).toString
  }

  override def toSerializationFormatWithAddress(addr: Address): String = {
    val diff = addressStringLengthDiff(addr)
    val length = toStringLength + diff
    val sb = buildToString(new JStringBuilder(length + 12), length, diff, _.toStringWithAddress(addr))
    sb.toString
  }

  private def addressStringLengthDiff(addr: Address): Int = {
    val r = root
    /*if (r.address.host.isDefined) 0
    else*/ (addr.toString.length - r.address.toString.length)
  }

  /**
   * Optimized toString construction. Used by `toString`, `toSerializationFormat`,
   * and friends `WithAddress`
   * @param sb builder that will be modified (and same instance is returned)
   * @param length pre-calculated length of the to be constructed String, not
   *   necessarily same as sb.capacity because more things may be appended to the
   *   sb afterwards
   * @param diff difference in offset for each child element, due to different address
   * @param rootString function to construct the root element string
   */
  private def buildToString(sb: JStringBuilder, length: Int, diff: Int, rootString: RootActorPath ⇒ String): JStringBuilder = {
    @tailrec
    def rec(p: ActorPath): JStringBuilder = p match {
      case r: RootActorPath =>
        val rootStr = rootString(r)
        sb.replace(0, rootStr.length, rootStr)
      case c: ChildActorPath =>
        val start = c.toStringOffset + diff
        val end = start + c.name.length
        sb.replace(start, end, c.name)
        if (c ne this)
          sb.replace(end, end + 1, "/")
        rec(c.parent)
    }

    sb.setLength(length)
    rec(this)
  }

  override def compareTo(other: ActorPath): Int = {
    @tailrec
    def rec(left: ActorPath, right: ActorPath): Int =
      if (left eq right) 0
      else if (left.isInstanceOf[RootActorPath]) left compareTo right
      else if (right.isInstanceOf[RootActorPath]) -(right compareTo left)
      else {
        val x = left.name compareTo right.name
        if (x == 0) rec(left.parent, right.parent)
        else x
      }

    rec(this, other)
  }
}
