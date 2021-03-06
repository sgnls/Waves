package com.wavesplatform.lang

import com.wavesplatform.lang.Common._
import com.wavesplatform.lang.Terms._
import com.wavesplatform.lang.ctx._
import com.wavesplatform.lang.TypeChecker.{TypeCheckerContext, TypeCheckResult, TypeDefs}
import com.wavesplatform.lang.testing.ScriptGen
import monix.eval.Coeval
import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, PropSpec}
import scodec.bits.ByteVector

class TypeCheckerTest extends PropSpec with PropertyChecks with Matchers with ScriptGen with NoShrink {

  private val pointType = PredefType("Point", List("x" -> LONG, "y" -> LONG))

  property("should infer generic function return type") {
    import Untyped._
    val genericFuncWithTwoTypeParams = PredefFunction("genericFunc", TYPEREF("T"), List("p1" -> TYPEREF("T")))(Right(_))
    val ctx                          = Context(Map.empty, Map.empty, functions = Map((genericFuncWithTwoTypeParams.name, genericFuncWithTwoTypeParams)))
    val Right(v)                     = TypeChecker(TypeCheckerContext.fromContext(ctx), FUNCTION_CALL(genericFuncWithTwoTypeParams.name, List(CONST_LONG(1))))

    v.tpe shouldBe LONG
  }

  property("should infer inner types") {
    import Untyped._
    val genericFuncWithOptionParam = PredefFunction("genericFunc", TYPEREF("T"), List("p1" -> OPTION(TYPEREF("T")))) {
      case Some(vl) :: Nil => Right(vl)
      case _               => Left("extracting from empty option")
    }

    val optionFunc = PredefFunction("optionFunc", OPTION(LONG), List.empty)(null)

    val ctx =
      Context(Map.empty, Map.empty, functions = Map((genericFuncWithOptionParam.name, genericFuncWithOptionParam), (optionFunc.name, optionFunc)))
    val Right(v) = TypeChecker(TypeCheckerContext.fromContext(ctx),
                               FUNCTION_CALL(genericFuncWithOptionParam.name, List(FUNCTION_CALL(optionFunc.name, List.empty))))

    v.tpe shouldBe LONG
  }

  rootTypeTest("successful on very deep expressions (stack overflow check)")(
    expr = (1 to 100000).foldLeft[Untyped.EXPR](Untyped.CONST_LONG(0))((acc, _) => Untyped.BINARY_OP(acc, SUM_OP, Untyped.CONST_LONG(1))),
    expectedResult = Right(LONG)
  )

  {
    import Typed._

    treeTypeErasureTests(
      "CONST_LONG"        -> CONST_LONG(0),
      "CONST_BYTEVECTOR" -> CONST_BYTEVECTOR(ByteVector(1, 2, 3)),
      "TRUE"             -> TRUE,
      "FALSE"            -> FALSE,
      "NONE"             -> NONE,
      "SUM"              -> BINARY_OP(CONST_LONG(0), SUM_OP, CONST_LONG(1), LONG),
      "AND"              -> BINARY_OP(TRUE, AND_OP, FALSE, BOOLEAN),
      "OR"               -> BINARY_OP(TRUE, OR_OP, FALSE, BOOLEAN),
      "EQ(LONG)"          -> BINARY_OP(CONST_LONG(0), EQ_OP, CONST_LONG(1), BOOLEAN),
      "EQ(BOOL)"         -> BINARY_OP(TRUE, EQ_OP, FALSE, BOOLEAN),
      "GT"               -> BINARY_OP(CONST_LONG(0), GT_OP, CONST_LONG(1), BOOLEAN),
      "GE"               -> BINARY_OP(CONST_LONG(0), GE_OP, CONST_LONG(1), BOOLEAN),
      "BLOCK" -> BLOCK(
        let = None,
        body = CONST_LONG(0),
        tpe = LONG
      ),
      "IF" -> IF(
        cond = TRUE,
        ifTrue = SOME(TRUE, OPTION(BOOLEAN)),
        ifFalse = NONE,
        tpe = OPTION(BOOLEAN)
      ),
      "SOME"      -> SOME(TRUE, OPTION(BOOLEAN))
    )
  }

  treeTypeTest("GETTER")(
    ctx = TypeCheckerContext(predefTypes = Map(pointType.name -> pointType), varDefs = Map("p" -> TYPEREF("Point")), functionDefs = Map.empty),
    expr = Untyped.GETTER(
      ref = Untyped.REF("p"),
      field = "x"
    ),
    expectedResult = Right(
      Typed.GETTER(
        ref = Typed.REF("p", TYPEREF("Point")),
        field = "x",
        tpe = LONG
      ))
  )

  treeTypeTest("REF(OBJECT)")(
    ctx = TypeCheckerContext(predefTypes = Map(pointType.name -> pointType), varDefs = Map("p" -> TYPEREF("Point")), functionDefs = Map.empty),
    expr = Untyped.REF("p"),
    expectedResult = Right(Typed.REF("p", TYPEREF("Point")))
  )

  treeTypeTest("REF x = y")(
    ctx = TypeCheckerContext(predefTypes = Map(pointType.name -> pointType), varDefs = Map("p" -> TYPEREF("Point")), functionDefs = Map.empty),
    expr = Untyped.REF("p"),
    expectedResult = Right(Typed.REF("p", TYPEREF("Point")))
  )

  treeTypeTest("MULTIPLY(1,2)")(
    ctx =
      TypeCheckerContext(predefTypes = Map.empty, varDefs = Map.empty, functionDefs = Map(multiplierFunction.name -> multiplierFunction.signature)),
    expr = Untyped.FUNCTION_CALL(multiplierFunction.name, List(Untyped.CONST_LONG(1), Untyped.CONST_LONG(2))),
    expectedResult = Right(Typed.FUNCTION_CALL(multiplierFunction.name, List(Typed.CONST_LONG(1), Typed.CONST_LONG(2)), LONG))
  )

  private val optFunc     = PredefFunction("OPTFUNC", UNIT, List("opt"            -> OPTION(OPTION(LONG))))(_ => Right(()))
  private val noneFunc    = PredefFunction("NONEFUNC", UNIT, List("opt"           -> OPTION(NOTHING)))(_ => Right(()))
  private val genericFunc = PredefFunction("genericFunc", TYPEREF("T"), List("p1" -> TYPEREF("T"), "p2" -> TYPEREF("T")))(Right(_))

  treeTypeTest(s"NONEFUNC(NONE)")(
    ctx = TypeCheckerContext(predefTypes = Map.empty, varDefs = Map.empty, functionDefs = Map(noneFunc.name -> noneFunc.signature)),
    expr = Untyped.FUNCTION_CALL(noneFunc.name, List(Untyped.NONE)),
    expectedResult = Right(Typed.FUNCTION_CALL(noneFunc.name, List(Typed.NONE), UNIT))
  )

  treeTypeTest(s"OPTFUNC(NONE)")(
    ctx = TypeCheckerContext(predefTypes = Map.empty, varDefs = Map.empty, functionDefs = Map(optFunc.name -> optFunc.signature)),
    expr = Untyped.FUNCTION_CALL(optFunc.name, List(Untyped.NONE)),
    expectedResult = Right(Typed.FUNCTION_CALL(optFunc.name, List(Typed.NONE), UNIT))
  )

  treeTypeTest(s"OPTFUNC(SOME(NONE))")(
    ctx = TypeCheckerContext(predefTypes = Map.empty, varDefs = Map.empty, functionDefs = Map(optFunc.name -> optFunc.signature)),
    expr = Untyped.FUNCTION_CALL(optFunc.name, List(Untyped.SOME(Untyped.NONE))),
    expectedResult = Right(Typed.FUNCTION_CALL(optFunc.name, List(Typed.SOME(Typed.NONE, OPTION(OPTION(NOTHING)))), UNIT))
  )

  treeTypeTest(s"OPTFUNC(SOME(CONST_LONG(3)))")(
    ctx = TypeCheckerContext(predefTypes = Map.empty, varDefs = Map.empty, functionDefs = Map(optFunc.name -> optFunc.signature)),
    expr = Untyped.FUNCTION_CALL(optFunc.name, List(Untyped.SOME(Untyped.SOME(Untyped.CONST_LONG(3))))),
    expectedResult =
      Right(Typed.FUNCTION_CALL(optFunc.name, List(Typed.SOME(Typed.SOME(Typed.CONST_LONG(3), OPTION(LONG)), OPTION(OPTION(LONG)))), UNIT))
  )

  {
    import Untyped._

    errorTests(
      "BINARY_OP with wrong types"                   -> "The first operand is expected to be LONG" -> BINARY_OP(TRUE, SUM_OP, CONST_LONG(1)),
      "IF can't find common"                         -> "Can't find common type" -> IF(TRUE, TRUE, CONST_LONG(0)),
      "FUNCTION_CALL with wrong amount of arguments" -> "requires 2 arguments" -> FUNCTION_CALL(multiplierFunction.name, List(CONST_LONG(0))),
      "FUNCTION_CALL with upper type"                -> "do not match types required" -> FUNCTION_CALL(noneFunc.name, List(SOME(CONST_LONG(3)))),
      "FUNCTION_CALL with wrong type of argument"    -> "Types of arguments of function call" -> FUNCTION_CALL(multiplierFunction.name,
                                                                                                            List(CONST_LONG(0), FALSE)),
      "FUNCTION_CALL with uncommon types for parameter T" -> "is no common type" -> FUNCTION_CALL(genericFunc.name,
                                                                                                  List(CONST_LONG(1),
                                                                                                       CONST_BYTEVECTOR(ByteVector.empty)))
    )
  }

  private def rootTypeTest(propertyName: String)(expr: Untyped.EXPR,
                                                 expectedResult: TypeCheckResult[TYPE],
                                                 varDefs: TypeDefs = Map.empty,
                                                 predefTypes: Map[String, PredefType] = Map.empty): Unit = property(propertyName) {
    TypeChecker(TypeCheckerContext(predefTypes, varDefs, Map.empty), expr).map(_.tpe) match {
      case Right(x)    => Right(x) shouldBe expectedResult
      case e @ Left(_) => e shouldBe expectedResult
    }
  }

  private def errorTests(exprs: ((String, String), Untyped.EXPR)*): Unit = exprs.foreach {
    case ((label, error), input) =>
      property(s"Error: $label") {
        TypeChecker(
          TypeCheckerContext(
            Map.empty,
            Map.empty,
            Map(
              multiplierFunction.name -> multiplierFunction.signature,
              noneFunc.name           -> noneFunc.signature,
              genericFunc.name        -> genericFunc.signature
            )
          ),
          input
        ) should produce(error)
      }
  }

  private def treeTypeTest(propertyName: String)(expr: Untyped.EXPR, expectedResult: TypeCheckResult[Typed.EXPR], ctx: TypeCheckerContext): Unit =
    property(propertyName) {
      TypeChecker(ctx, expr) shouldBe expectedResult
    }

  private def treeTypeErasureTests(exprs: (String, Typed.EXPR)*): Unit = exprs.foreach {
    case (exprName, expected) =>
      property(exprName) {
        val erased = erase(expected)
        TypeChecker(TypeCheckerContext.empty, erased) shouldBe Right(expected)
      }
  }

  private def erase(expr: Typed.EXPR): Untyped.EXPR = {
    import cats.syntax.apply._

    def aux(root: Typed.EXPR): Coeval[Untyped.EXPR] =
      Coeval.defer(root match {
        case x: Typed.CONST_LONG        => Coeval(Untyped.CONST_LONG(x.t))
        case x: Typed.CONST_BYTEVECTOR => Coeval(Untyped.CONST_BYTEVECTOR(x.bs))
        case Typed.TRUE                => Coeval(Untyped.TRUE)
        case Typed.FALSE               => Coeval(Untyped.FALSE)
        case Typed.NONE                => Coeval(Untyped.NONE)

        case getter: Typed.GETTER        => aux(getter.ref).map(Untyped.GETTER(_, getter.field))
        case binaryOp: Typed.BINARY_OP   => (aux(binaryOp.a), aux(binaryOp.b)).mapN(Untyped.BINARY_OP(_, binaryOp.kind, _))
        case block: Typed.BLOCK =>
          aux(block.body).flatMap { t =>
            val x = Untyped.BLOCK(let = None, body = t)
            block.let match {
              case None => Coeval(x)
              case Some(let) =>
                aux(let.value).map {
                  case let: Untyped.LET => x.copy(let = Some(let))
                  case _                => throw new IllegalStateException()
                }
            }
          }
        case ifExpr: Typed.IF       => (aux(ifExpr.cond), aux(ifExpr.ifTrue), aux(ifExpr.ifFalse)).mapN(Untyped.IF)
        case ref: Typed.REF         => Coeval(Untyped.REF(ref.key))
        case some: Typed.SOME       => aux(some.t).map(Untyped.SOME)
        case f: Typed.FUNCTION_CALL =>
          import cats.instances.vector._
          import cats.syntax.all._
          val auxedArgs: Vector[Coeval[Untyped.EXPR]] = f.args.map(aux).toVector
          val sequencedAuxedArgs: Coeval[Vector[Untyped.EXPR]] = auxedArgs.sequence
          sequencedAuxedArgs.map(args => Untyped.FUNCTION_CALL(f.functionName,args.toList))
      })

    aux(expr)()
  }
}
