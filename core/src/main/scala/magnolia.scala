package magnolia

import scala.reflect._, macros._
import macrocompat.bundle
import scala.util.Try
import language.existentials
import language.higherKinds

@bundle
class Macros(val c: whitebox.Context) {
  import c.universe._
  import CompileTimeState._

  private def findType(key: Type): Option[TermName] =
    recursionStack(c.enclosingPosition).frames.find(_.genericType == key).map(_.termName(c))

  private def recurse[T](path: TypePath, key: Type, value: TermName)(fn: => T):
      Option[T] = {
    recursionStack = recursionStack.updated(
      c.enclosingPosition,
      recursionStack.get(c.enclosingPosition).map(_.push(path, key, value)).getOrElse(
          Stack(List(Frame(path, key, value)), Nil))
    )
    
    try Some(fn) catch { case e: Exception => None } finally {
      val currentStack = recursionStack(c.enclosingPosition)
      recursionStack = recursionStack.updated(c.enclosingPosition,
          currentStack.pop())
    }
  }
  
  private val removeLazy: Transformer = new Transformer {
    override def transform(tree: Tree): Tree = tree match {
      case q"_root_.magnolia.Lazy.apply[$returnType](${Literal(Constant(method: String))})" =>
        q"${TermName(method)}"
      case _ =>
        super.transform(tree)
    }
  }
  
  private def getImplicit(paramName: Option[String],
                          genericType: Type,
                          typeConstructor: Type,
                          assignedName: TermName,
                          derivationImplicit: Either[Tree, Tree]): Tree = {
    
    findType(genericType).map { methodName =>
      val methodAsString = methodName.encodedName.toString
      val searchType = appliedType(typeConstructor, genericType)
      q"_root_.magnolia.Lazy.apply[$searchType]($methodAsString)"
    }.orElse {
      val searchType = appliedType(typeConstructor, genericType)
      findType(genericType).map { _ =>
        directInferImplicit(genericType, typeConstructor, derivationImplicit)
      }.getOrElse {
        scala.util.Try {
          val genericTypeName: String = genericType.typeSymbol.name.encodedName.toString.toLowerCase
          val assignedName: TermName = TermName(c.freshName(s"${genericTypeName}Typeclass"))
          recurse(ChainedImplicit(genericType.toString), genericType, assignedName) {
            val inferredImplicit = c.inferImplicitValue(searchType, false, false)
            q"""{
              def $assignedName: $searchType = $inferredImplicit
              $assignedName
            }"""
          }.get
        }.toOption.orElse(directInferImplicit(genericType, typeConstructor, derivationImplicit))
      }
    }.getOrElse {
      val currentStack: Stack = recursionStack(c.enclosingPosition)
      
      val error = ImplicitNotFound(genericType.toString,
          recursionStack(c.enclosingPosition).frames.map(_.path))
      
      val updatedStack = currentStack.copy(errors = error :: currentStack.errors) 
      recursionStack = recursionStack.updated(c.enclosingPosition, updatedStack)
      c.abort(c.enclosingPosition, s"Could not find type class for type $genericType")
    }
  }
  
  private def directInferImplicit(genericType: Type,
         typeConstructor: Type,
         derivationImplicit: Either[Tree, Tree]): Option[Tree] = {

    val genericTypeName: String = genericType.typeSymbol.name.encodedName.toString.toLowerCase
    val assignedName: TermName = TermName(c.freshName(s"${genericTypeName}Typeclass"))
    val typeSymbol = genericType.typeSymbol
    val classType = if(typeSymbol.isClass) Some(typeSymbol.asClass) else None
    val isCaseClass = classType.map(_.isCaseClass).getOrElse(false)
    val isSealedTrait = classType.map(_.isSealed).getOrElse(false)
    val isValueClass = genericType <:< typeOf[AnyVal]
    
    val resultType = appliedType(typeConstructor, genericType)

    val construct = if(isCaseClass) {
      val implicits = genericType.decls.collect {
        case m: MethodSymbol if m.isCaseAccessor => m.asMethod
      }.map { param =>
        val paramName = param.name.encodedName.toString
        
        val derivedImplicit = recurse(ProductType(paramName, genericType.toString), genericType,
            assignedName) {
          
          getImplicit(Some(paramName), param.returnType, typeConstructor, assignedName,
              derivationImplicit)
        
        }.getOrElse {
          c.abort(c.enclosingPosition, s"failed to get implicit for type $genericType")
        }
        
        derivationImplicit match {
          case Left(impl) =>
            val dereferencedValue = q"$impl.dereference(sourceParameter, ${param.name.toString})"
            q"$impl.call($derivedImplicit, $dereferencedValue)"
          case Right(impl) => 
            val paramName = TermName(param.name.toString)
            val dereferencedValue = q"sourceParameter.$paramName"
            q"$impl.call($derivedImplicit, $dereferencedValue)"
        }
      }

      derivationImplicit match {
        case Left(_) =>
          Some(q"new $genericType(..$implicits)")
        case Right(impl) =>
          Some(q"$impl.join(_root_.scala.List(..$implicits))")
      }
    } else if(isSealedTrait) {

      val subtypes = classType.get.knownDirectSubclasses.to[List]
      
      Some {
        val components = subtypes.map(_.asType.toType).map { searchType =>
          recurse(CoproductType(genericType.toString), genericType, assignedName) {
            getImplicit(None, searchType, typeConstructor, assignedName, derivationImplicit)
          }.getOrElse {
            c.abort(c.enclosingPosition, s"failed to get implicit for type $searchType")
          }
        }
        
        derivationImplicit match {
          case Left(impl) =>
            val reduction = components.reduce { (left, right) => q"$impl.combine($left, $right)" }
            q"$impl.call($reduction, sourceParameter)"
          case Right(impl) =>
            val parts = subtypes.tail.zip(components.tail)
            
            val base = q"""
              $impl.call(${components.head}, sourceParameter.asInstanceOf[${subtypes.head}])
            """
            
            parts.foldLeft(base) { case (aggregated, (componentType, derivedImplicit)) =>
              q"""
                if(sourceParameter.isInstanceOf[$componentType])
                  $impl.call($derivedImplicit, sourceParameter.asInstanceOf[$componentType])
                else $aggregated"""
            }
        }
      }
    } else None

    construct.map { const =>
      val impl = derivationImplicit.merge
      val res = q"""{
        def $assignedName: $resultType = $impl.construct { sourceParameter => $const }
        $assignedName
      }"""

      try c.typecheck(res) catch {
        case e: Exception =>
          e.printStackTrace()
      }
      res
    }
  }
  
  def magnolia[T: WeakTypeTag, Typeclass: WeakTypeTag]: Tree = try {

    val genericType: Type = weakTypeOf[T]
    val currentStack: Stack = recursionStack.get(c.enclosingPosition).getOrElse(Stack(List(), List()))
    val directlyReentrant = Some(genericType) == currentStack.frames.headOption.map(_.genericType)
    val typeConstructor: Type = weakTypeOf[Typeclass].typeConstructor
    
    val coDerivationTypeclass = weakTypeOf[CovariantDerivation[_]].typeConstructor
    val contraDerivationTypeclass = weakTypeOf[ContravariantDerivation[_]].typeConstructor
    
    val coDerivationType = appliedType(coDerivationTypeclass, List(typeConstructor))
    val contraDerivationType = appliedType(contraDerivationTypeclass, List(typeConstructor))
    val derivationImplicit = try {
      Left(c.untypecheck(c.inferImplicitValue(coDerivationType, false, false)))
    } catch {
      case e: Exception =>
        Right(c.untypecheck(c.inferImplicitValue(contraDerivationType)))
    }
    
    if(directlyReentrant) throw DirectlyReentrantException()
   
    currentStack.errors.foreach { error =>
      if(!emittedErrors.contains(error)) {
        emittedErrors += error
        val trace = error.path.mkString("\n    in ", "\n    in ", "\n \n")
        val msg = s"could not derive ${typeConstructor} instance for type ${error.genericType}"
        c.info(c.enclosingPosition, msg+trace, true)
      }
    }

    val result: Option[Tree] = if(!currentStack.frames.isEmpty) {
      findType(genericType) match {
        case None =>
          directInferImplicit(genericType, typeConstructor, derivationImplicit)
        case Some(enclosingRef) =>
          val methodAsString = enclosingRef.toString
          val searchType = appliedType(typeConstructor, genericType)
          Some(q"_root_.magnolia.Lazy[$searchType]($methodAsString)")
      }
    } else {
      val typeConstructor: Type = weakTypeOf[Typeclass].typeConstructor
      directInferImplicit(genericType, typeConstructor, derivationImplicit)
    }
   
    if(currentStack.frames.isEmpty) recursionStack = Map()

    result.map { tree =>
      if(currentStack.frames.isEmpty) {
        val res = c.untypecheck(removeLazy.transform(tree))
        res
      } else tree
    }.getOrElse {
      c.abort(c.enclosingPosition, "could not infer typeclass for type $genericType")
    }

  } catch {
    case DirectlyReentrantException() => ???
    case e: Exception => e.printStackTrace(); ???
  }
}

private[magnolia] case class DirectlyReentrantException() extends
    Exception("attempt to recurse directly")

private[magnolia] object Lazy { def apply[T](method: String): T = ??? }

private[magnolia] object CompileTimeState {

  sealed trait TypePath
  case class CoproductType(typeName: String) extends TypePath {
    override def toString = s"coproduct type $typeName"
  }

  case class ProductType(paramName: String, typeName: String) extends TypePath {
    override def toString = s"parameter '$paramName' of product type $typeName"
  }

  case class ChainedImplicit(typeName: String) extends TypePath {
    override def toString = s"chained implicit of type $typeName"
  }

  case class ImplicitNotFound(genericType: String, path: List[TypePath])

  case class Stack(frames: List[Frame], errors: List[ImplicitNotFound]) {
    
    def push(path: TypePath, key: whitebox.Context#Type,
        value: whitebox.Context#TermName): Stack =
      Stack(Frame(path, key, value) :: frames, errors)
    
    def pop(): Stack = Stack(frames.tail, errors)
  }

  case class Frame(path: TypePath, genericType: whitebox.Context#Type,
      term: whitebox.Context#TermName) {
    def termName(c: whitebox.Context): c.TermName = term.asInstanceOf[c.TermName]
  }

  private[magnolia] var recursionStack: Map[api.Position, Stack] =
    Map()
  
  private[magnolia] var emittedErrors: Set[ImplicitNotFound] = Set()
}

trait CovariantDerivation[Typeclass[_]] {
  type Value
  def dereference(value: Value, param: String): Value
  def call[T](typeclass: Typeclass[T], value: Value): T
  def construct[T](body: Value => T): Typeclass[T]
  
  def combine[Supertype, Right <: Supertype](left: Typeclass[_ <: Supertype],
      right: Typeclass[Right]): Typeclass[Supertype]
}

trait ContravariantDerivation[Typeclass[_]] {
  type Return
  def call[T](typeclass: Typeclass[T], value: T): Return
  def construct[T](body: T => Return): Typeclass[T]
  def join(elements: List[Return]): Return

}
