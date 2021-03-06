/* Copyright 2009-2015 EPFL, Lausanne */

package leon
package xlang

import purescala.Common._
import purescala.Types._
import purescala.Expressions._
import purescala.Extractors._
import purescala.{PrettyPrintable, PrinterContext}
import utils._

object Expressions {
  import purescala.PrinterHelpers._

  trait XLangExpr extends Expr

  case class Block(exprs: Seq[Expr], last: Expr) extends XLangExpr with Extractable with PrettyPrintable {
    def extract: Option[(Seq[Expr], (Seq[Expr])=>Expr)] = {
      Some((exprs :+ last, exprs => Block(exprs.init, exprs.last)))
    }

    override def getPos = {
      Position.between(exprs.head.getPos, last.getPos)
    }

    def printWith(implicit pctx: PrinterContext) {
      p"${nary(exprs :+ last, "\n")}"
    }

    val getType = last.getType

    override def isSimpleExpr = false
  }

  case class Assignment(varId: Identifier, expr: Expr) extends XLangExpr with Extractable with PrettyPrintable {
    val getType = UnitType

    def extract: Option[(Seq[Expr], Seq[Expr]=>Expr)] = {
      Some((Seq(expr), (es: Seq[Expr]) => Assignment(varId, es.head)))
    }

    def printWith(implicit pctx: PrinterContext) {
      p"$varId = $expr;"
    }
  }

  case class While(cond: Expr, body: Expr) extends XLangExpr with Extractable with PrettyPrintable {
    val getType = UnitType
    var invariant: Option[Expr] = None

    def getInvariant: Expr = invariant.get
    def setInvariant(inv: Expr) = { invariant = Some(inv); this }
    def setInvariant(inv: Option[Expr]) = { invariant = inv; this }

    def extract: Option[(Seq[Expr], Seq[Expr]=>Expr)] = {
      Some((Seq(cond, body) ++ invariant, { (es:Seq[Expr]) => es match {
        case Seq(e1, e2) => While(e1, e2).setPos(this)
        case Seq(e1, e2, e3) => While(e1, e2).setInvariant(e3).setPos(this)
      }}))
    }

    def printWith(implicit pctx: PrinterContext) {
      invariant match {
        case Some(inv) =>
          p"""|@invariant($inv)
              |"""
        case None =>
      }

      p"""|while($cond) {
          |  $body
          |}"""
    }
  }

  case class Epsilon(pred: Expr, tpe: TypeTree) extends XLangExpr with Extractable with PrettyPrintable {
    def extract: Option[(Seq[Expr], Seq[Expr]=>Expr)] = {
      Some((Seq(pred), (es: Seq[Expr]) => Epsilon(es.head, this.getType).setPos(this)))
    }

    def printWith(implicit pctx: PrinterContext) {
      p"epsilon(x${getPos.line}_${getPos.col}. $pred)"
    }

    val getType = tpe
  }

  case class EpsilonVariable(pos: Position, tpe: TypeTree) extends XLangExpr with Terminal with PrettyPrintable {

    def printWith(implicit pctx: PrinterContext) {
      p"x${pos.line}_${pos.col}"
    }

    val getType = tpe
  }

  //same as let, buf for mutable variable declaration
  case class LetVar(binder: Identifier, value: Expr, body: Expr) extends XLangExpr with Extractable with PrettyPrintable {
    val getType = body.getType

    def extract: Option[(Seq[Expr], Seq[Expr]=>Expr)] = {
      Some( Seq(value, body), (es:Seq[Expr]) => LetVar(binder, es(0), es(1)) )
    }

    def printWith(implicit pctx: PrinterContext) {
      p"""|var $binder = $value
          |$body"""
    }

    override def isSimpleExpr = false
  }

  case class Waypoint(i: Int, expr: Expr, tpe: TypeTree) extends XLangExpr with Extractable with PrettyPrintable{
    def extract: Option[(Seq[Expr], Seq[Expr]=>Expr)] = {
      Some((Seq(expr), (es: Seq[Expr]) => Waypoint(i, es.head, tpe)))
    }

    def printWith(implicit pctx: PrinterContext) {
      p"waypoint_$i($expr)"
    }

    val getType = tpe
  }

  case class ArrayUpdate(array: Expr, index: Expr, newValue: Expr) extends XLangExpr with Extractable with PrettyPrintable {
    val getType = UnitType

    def extract: Option[(Seq[Expr], (Seq[Expr])=>Expr)] = {
      val ArrayUpdate(t1, t2, t3) = this
      Some((Seq(t1,t2,t3), (as: Seq[Expr]) => ArrayUpdate(as(0), as(1), as(2))))
    }

    def printWith(implicit pctx: PrinterContext) {
      p"$array($index) = $newValue"
    }
  }

}
