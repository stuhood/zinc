package xsbt

import scala.tools.nsc.Global

/**
 * Utility methods for creating (source|binary) class names for a Symbol.
 */
trait ClassName {
  val global: Global
  import global._

  /**
   * Creates a flat (binary) name for a class symbol `s`.
   */
  protected def flatname(s: Symbol, separator: Char) =
    enteringPhase(currentRun.flattenPhase.next) { s fullName separator }

  /**
   * Create a (source) name for a class symbol `s`.
   */
  protected def className(s: Symbol): String = pickledName(s)

  private def pickledName(s: Symbol): String =
    enteringPhase(currentRun.picklerPhase.next) { s.fullName }

  protected def isTopLevelModule(sym: Symbol): Boolean =
    enteringPhase(currentRun.picklerPhase.next) {
      sym.isModuleClass && !sym.isImplClass && !sym.isNestedClass
    }

  protected def flatclassName(s: Symbol, sep: Char, dollarRequired: Boolean): String =
    flatname(s, sep) + (if (dollarRequired) "$" else "")
}
