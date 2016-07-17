package sbt.internal.inc.javac

import java.util.{ Set => JSet }
import java.lang.{ Iterable => JIterable }
import sbt.util.Logger
import javax.tools.{
  ForwardingJavaFileManager,
  FileObject,
  JavaFileManager,
  JavaFileObject,
  StandardJavaFileManager => StandardJFM
}

/**
 * Extends ForwardingJavaFileManager to log all events which might be relevant to eventually
 * writing classfiles to memory or jars.
 */
class LoggingJavaFileManager(
  m: StandardJFM,
  private val log: Logger
) extends ForwardingJavaFileManager[StandardJFM](m: StandardJFM) {

  override def close(): Unit = logged("close") {
    fm.close()
  }

  override def flush(): Unit = logged("close") {
    fm.flush()
  }

  override def getClassLoader(location: JavaFileManager.Location): ClassLoader = logged("getClassLoader") {
    fm.getClassLoader(location)
  }

  override def getFileForInput(location: JavaFileManager.Location, packageName: String, relativeName: String): FileObject = logged("getFileForInput") {
    fm.getFileForInput(location, packageName, relativeName)
  }

  override def getFileForOutput(location: JavaFileManager.Location, packageName: String, relativeName: String, sibling: FileObject): FileObject = logged("getFileForOutput") {
    fm.getFileForOutput(location, packageName, relativeName, sibling)
  }

  override def getJavaFileForInput(location: JavaFileManager.Location, className: String, kind: JavaFileObject.Kind): JavaFileObject = logged("getJavaFileForInput") {
    fm.getJavaFileForInput(location, className, kind)
  }

  override def getJavaFileForOutput(location: JavaFileManager.Location, className: String, kind: JavaFileObject.Kind, sibling: FileObject): JavaFileObject = logged("getJavaFileForOutput") {
    fm.getJavaFileForOutput(location, className, kind, sibling)
  }

  override def isSameFile(a: FileObject, b: FileObject): Boolean = logged("isSameFile") {
    fm.isSameFile(a, b)
  }

  override def list(location: JavaFileManager.Location, packageName: String, kinds: JSet[JavaFileObject.Kind], recurse: Boolean): JIterable[JavaFileObject] = logged("list") {
    fm.list(location, packageName, kinds, recurse)
  }

  private def logged[T](methodName: String)(body: => T): T =
    try {
      val t: T = body
      log.info(s"LoggingJavaFileManager: $methodName returned: $t")
      t
    } catch {
      case e: Throwable =>
        log.info(s"LoggingJavaFileManager: $methodName threw: $e")
        throw e
    }

  private[this] def fm = this.fileManager
}
