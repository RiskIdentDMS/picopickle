picopickle 0.3.0
================

picopickle is a serialization library for Scala. Its main features are:

* Small and almost dependency-less (the core library depends only on [shapeless]).
* Extensibility: you can define your own serializators for your types and you can create
  custom *backends*, that is, you can use the same library for the different serialization formats
  (collections, JSON, BSON, etc.); other parts of the serialization behavior like nulls handling
  can also be customized.
* Flexibility and convenience: the default serialization format is fine for most uses, but it can
  be customized almost arbitrarily with support from a convenient converters DSL.
* Static serialization without reflection: shapeless [`Generic`][Generic] macros are used to
  provide serializers for arbitrary types, which means that no reflection is used.

  [shapeless]: https://github.com/milessabin/shapeless
  [Generic]: https://github.com/milessabin/shapeless/wiki/Feature-overview:-shapeless-2.0.0#generic-representation-of-sealed-families-of-case-classes

Contents
--------

* [Getting started](#getting-started)
  + [Backend dependencies](#backend-dependencies)
* [Serialization mechanism](#serialization-mechanism)
* [Usage](#usage)
  + [Basic usage](#basic-usage)
  + [Serializer objects](#serializer-objects)
  + [Custom picklers](#custom-picklers)
  + [Backends](#backends)
  + [Extending backends](#extending-backends)
  + [Creating custom serializers](#creating-custom-serializers)
  + [Extractors and backend conversion implicits](#extractors-and-backend-conversion-implicits)
* [Converters](#converters)
* [Supported types](#supported-types)
  + [Primitives and basic types](#primitives-and-basic-types)
  + [Numbers and accuracy](#numbers-and-accuracy)
  + [Tuples](#tuples)
  + [Collections](#collections)
  + [Map serialization with non-string keys](#map-serialization-with-non-string-keys)
  + [Sealed trait hierarchies](#sealed-trait-hierarchies)
  + [Changing the discriminator key](#changing-the-discriminator-key)
  + [Serialization of optional fields](#serialization-of-optional-fields)
  + [Renaming fields and sealed trait variants](#renaming-fields-and-sealed-trait-variants)
  + [Default values of case class fields](#default-values-of-case-class-fields)
  + [Varargs](#varargs)
  + [Nulls](#nulls)
  + [Accurate numbers serialization](#accurate-numbers-serialization)
  + [Value classes](#value-classes)
* [Official backends](#official-backends)
  + [Collections pickler](#collections-pickler)
  + [JSON pickler](#json-pickler)
  + [BSON pickler](#bson-pickler)
* [Error handling](#error-handling)
* [Limitations](#limitations)
* [Changelog](#changelog)

<a name="getting-started"></a> Getting started
----------------------------------------------

The library is published to the Maven central, so you can just add the following line
to your `build.sbt` file in order to use the core library:

```scala
libraryDependencies += "io.github.netvl.picopickle" %% "picopickle-core" % "0.3.0"
```

The library is compiled for both 2.10 and 2.11 Scala versions. If you use 2.10, however,
you will need to add [Macro Paradise] compiler plugin because shapeless macros depend on it:

```scala
libraryDependencies += compilerPlugin("org.scalamacros" %% "paradise" % "2.0.1" cross CrossVersion.full)
// or
addCompilerPlugin("org.scalamacros" %% "paradise" % "2.0.1" cross CrossVersion.full)
```

Scala 2.11 users do not need this as all relevant macro support is already present in 2.11.

  [Macro Paradise]: http://docs.scala-lang.org/overviews/macros/paradise.html

### <a name="backend-dependencies"></a> Backend dependencies

Picopickle supports different *backends*. A backend defines the target serialization format,
for example, JSON, BSON or just regular collections. The core library provides collections
backend, and an additional JSON backend based on [Jawn] parser is available as
`picopickle-backend-jawn`:

```scala
libraryDependencies += "io.github.netvl.picopickle" %% "picopickle-backend-jawn" % "0.3.0"
```

Jawn backend uses Jawn parser (naturally!) to read JSON strings but it uses custom renderer
to print JSON AST as a string in order to keep dependencies to the minimum. This renderer
is very basic and does not support pretty-printing; this is something which is likely to be
fixed in one of the future versions.

You can create your own backends to support your own data formats; more information on how
to do it is available below. It is likely that more officially supported backends will be
available later.

  [Jawn]: https://github.com/non/jawn

<a name="serialization-mechanism"></a> Serialization mechanism
--------------------------------------------------------------

picopickle uses the pretty standard typeclass approach where the way the type is serialized
or deserialized is defined through implicit objects (called `Reader[T]` and `Writer[T]` in picopickle)
in scope. The library defines corresponding instances for a lot of standard types:

* primitives and other basic types: `Byte`, `Short`, `Int`, `Long`, `Float`, `Double`, `Boolean`,
  `Char`, `String`, `Unit`, `Null`, `Symbol`, `Option[T]`, `Either[A, B]`;
* tuples (currently generated as a part of build process for lengths from 1 to 22);
* most of standard Scala collections;
* sealed trait hierarchies: case classes and case objects, possibly implementing some sealed trait,
  and the sealed trait itself.

Serializers for sealed trait hierarchies are derived automatically with the help of shapeless
`LabelledGeneric` type class. The library defines several generic instances for the core shapeless
types (`HList` and `Coproduct`), and shapeless does the hard work of inspecting case classes
and sealed traits.

Since sealed trait hierarchies are equivalent to algebraic data types, their representation
with the shapeless type is fairly natural: each case class/case object is represented
by a `HList` of corresponding field types labelled with field names, and the whole hierarchy
is represented by a `Coproduct` of the corresponding types which implement the sealed trait.

picopickle also supports recursive types, that is, when a case class eventually depends on
itself or on the sealed trait it belongs to, for example:

```scala
sealed trait Root
case object A extends Root
case class B(x: Int, b: Option[B]) extends Root  // depends on itself
case class C(next: Root) extends Root  // depends on the sealed trait
```

picopickle also supports default values and variable arguments in case classes and renaming of fields 
or sealed trait descendants with a bit of custom macros.

<a name="usage"></a> Usage
--------------------------

### <a name="basic-usage"></a> Basic usage

picopickle is structured using the cake pattern, that is, it consists of several traits providing
parts of the functionality which are then combined into a single object called a *pickler*. It
provides everything necessary for the serialization via a glob import:

```scala
import some.package.SomePickler._
 
write("Hello") shouldEqual SomePicklerBackend.StringValue("Hello")
```

The core library and the Jawn backend library provide default picklers, so if you don't need
any customization (e.g. you don't need to define custom serializers for your types) you can just
import the internals of one of these picklers:

```scala
import io.github.netvl.picopickle.backends.collections.CollectionsPickler._
 
case class A(x: Int, y: String)
 
write(A(10, "hi")) shouldEqual Map("x" -> 10, "y" -> "hi")
read[A](Map("x" -> 10, "y" -> "hi")) shouldEqual A(10, "hi")
```

Jawn-based pickler also provides additional functions, `readString()`/`writeString()` and
`readAst()`/`writeAst()`, which [de]serialize objects to strings and JSON AST to strings,
respectively:

```scala
import io.github.netvl.picopickle.backends.jawn.JsonPickler._
 
case class A(x: Int, y: String)
 
writeString(A(10, "hi")) shouldEqual """{"x":10,"y":"hi"}"""
readString[A]("""{"x":10,"y":"hi"}""") shouldEqual A(10, "hi")
```

Currently the string JSON representation is not prettified (but prettification may be implemented in later versions).

### <a name="serializer-objects"></a> Serializer objects

Sometimes you need to work with serialization and deserializaton in the same piece of code (e.g. writing and reading
data from database). Then it would be convenient to have `read` and `write` methods fixed for some specific type,
both for correctness sake and in order to instantiate corresponding readers and writers in one place (which potentially
may speed up the compilation).

picopickle provides a special serializer class which can be constructed for any type which has `Reader` and `Writer`
instances. This class provides `read` and `write` methods specified for the type which this serializer is created for:

```scala
import io.github.netvl.picopickle.backends.collections.CollectionsPickler._
 
case class A(x: Int, y: String)

val aSerializer = serializer[A]
 
aSerializer.write(A(10, "hi")) shouldEqual Map("x" -> 10, "y" -> "hi")
// aSerializer.write("whatever")  // won't compile - write() accepts values of type A only

aSerializer.read(Map("x" -> 10, "y" -> "hi") shouldEqual A(10, "hi")
// val x: String = aSerializer.read("whatever")  // won't compile - read() returns values of type A
```

Jawn-based pickler extends this class to provide `readString` and `writeString` methods:

```scala
import io.github.netvl.picopickle.backends.jawn.JsonPickler._

case class A(x: Int, y: String)

val aSerializer = serializer[A]

aSerializer.writeString(A(10, "hi")) shouldEqual """{"x":10,"y":"hi"}"""
aSerializer.readString("""{"x":10,"y":"hi"}""") shouldEqual A(10, "hi")
```

### <a name="custom-picklers"></a> Custom picklers

It is possible that you would want to define custom serializers for some of your
types. In that case you can define custom serializer instances in a trait which "depends" on
`BackendComponent` and `TypesComponent` via a self type annotation:

```scala
import io.github.netvl.picopickle.{BackendComponent, TypesComponent}
 
case class DefinedByInt(x: Int, y: String)
 
trait CustomSerializers {
  this: BackendComponent with TypesComponent =>
 
  implicit val definedByIntWriter: Writer[DefinedByInt] = Writer {
    case DefinedByInt(x, _) => backend.makeNumber(x)
  }
 
  implicit val definedByIntReader: Reader[DefinedByInt] = Reader {
    case backend.Extract.Number(x) => DefinedByInt(x.intValue(), x.intValue().toString)
  }
}
```

Then this trait should be mixed into the corresponding pickler trait conveniently defined
in the library in order to create the pickler object:

```scala
import io.github.netvl.picopickle.backends.jawn.JsonPickler
 
object CustomPickler extends JsonPickler with CustomSerializers
```

You can also define the serializers directly in the pickler object if they are not supposed
to be reused or if you only have one pickler object in your program:

```scala
import io.github.netvl.picopickle.backends.jawn.JsonPickler
 
object CustomPickler extends JsonPickler {
  implicit val definedByIntWriter: Writer[DefinedByInt] = Writer {
    case DefinedByInt(x, _) => backend.makeNumber(x)
  }
 
  implicit val definedByIntReader: Reader[DefinedByInt] = Reader {
    case backend.Extract.Number(x) => DefinedByInt(x.intValue(), x.intValue().toString)
  }
}
```

Alternatively, you can import internals of some pickler and define serializers anywhere, but then you need
to add an import for wherever these serializers are defined in:

```
object CustomPickler extends JsonPickler

object CustomSerializers {
  import CustomPickler._
  
  implicit val definedByIntWriter: Writer[DefinedByInt] = Writer {
    case DefinedByInt(x, _) => backend.makeNumber(x)
  }
 
  implicit val definedByIntReader: Reader[DefinedByInt] = Reader {
    case backend.Extract.Number(x) => DefinedByInt(x.intValue(), x.intValue().toString)
  }
}

import CustomSerializers._

CustomPickler.write(DefinedByInt(10, "10")) shouldEqual """10"""
```

This way also prohibits you from using the same serializers for different kinds of picklers in your
program.

picopickle provides several utilities which help you writing custom serializers and deserializers; at first, however,
we need to explain what backends are.

### <a name="backends"></a> Backends

A *backend* in picopickle defines an intermediate AST called *backend representation* which is the media
which values of other types can be serialized into. For example, for JSON it is JSON AST, that is, a set of classes
which together can be used to form any correct JSON object tree. Additionally, a backend provides
methods to construct these values generically from basic Scala types and collections and to deconstruct
these values back into these basic types.

In general, a backend may be arbitrarily complex. It can consist of a lot of classes with various relationships
between them and all the necessary methods to construct them. However, in order to provide the ability to
serialize arbitrary types to arbitrary backend representations, some restrictions should be put on the structure
of the backend representation, that is, there should be some minimal set of primitives which should be supported
by all backends. picopickle requires that all backends support basic JSON-like tree AST, that is,
objects keyed by strings, arrays indexed by integers, strings, numbers, booleans and null. Using these primitives,
picopickle is able to provide serializers for basic primitive types and sealed trait hierarchies out of the box.

`Backend` trait is used to represent backends in Scala code. This trait contains abstract types which
define the AST and a lot of methods to construct the AST from basic types. Each implementation of this trait
should provide the following abstract types:

```scala
type BValue
type BObject <: BValue
type BArray <: BValue
type BString <: BValue
type BNumber <: BValue
type BBoolean <: BValue
type BNull <: BValue
```

Also each implementation must provide a set of methods for converting between these abstract types and basic Scala
types. The mapping is as follows:

```
BObject  -> Map[String, BValue]
BArray   -> Vector[BValue]
BString  -> String
BNumber  -> Number
BBoolean -> Boolean
BNull    -> Null
```

That is, each backend should provide methods to convert from `BValue` to `Map[String, BValue]` and back etc. These
methods can be divided into three groups:

* those which convert Scala values to backend representation: prefixed with `make`;
* those which convert backend representation to Scala values: prefixed with `from`;
* those which extract concrete backend type (e.g. `BObject`, `BString`) from the abstract `BValue`: prefixed with `get`.

The last group of methods return `Option[<corresponding type>]` because they are partial in their nature.

There are also some convenience methods like `makeEmptyObject` or `getArrayValueAt` which can be defined via
a conversion with the corresponding `from` method and then a query on the resulting Scala object, but these
methods may query the underlying backend representation directly, saving on the intermediate objects construction.

In order to create a custom backend you need to implement `Backend` trait first:

```scala
object MyBackend extends Backend {
  type BValue = ...
  ...
}
```

Then you need to create a cake component for this backend; this component must implement `BackendComponent` trait:

```scala
trait MyBackendComponent extends BackendComponent {
  override val backend = MyBackend
}
```

And finally you should extend `DefaultPickler`, mixing it with your backend component:

```scala
trait MyPickler extends DefaultPickler with MyBackendComponent
object MyPickler extends MyPickler
```

Naturally, you can choose not to merge the `DefaultPickler` fully into your pickler if you don't want to, for example,
if you don't need the automatic writers materialization for sealed trait hierarchies. In that case you can
mix in only those traits you need. See `DefaultPickler` documentation to find out which components it consists of
(**TODO**).

After this `MyPickler.read` and `MyPickler.write` methods will work with your backend representation.

### <a name="extending-backends"></a> Extending backends

Sometimes the set of types and methods provided by the default `Backend` trait is simply not enough
because the desired target representation supports more types. One example is BSON, which supports,
aside from all the standard JSON types, also things like date and time, object ids, explicit 32-bit
and 64-bit integers and byte arrays. Naturally, one would like to automatically serialize Scala types
to the most efficient representation available.

picopickle is extensible in this regard too. Because backends are just implementations of one trait,
nothing prevents you adding new concrete types into your backend implementation, adding new conversion
functions and build your own serializers which make use of these functions:

```scala
// Define a new backend
object BsonBackend extends Backend {
  // implement required types
  override type BValue = BsonValue
  override type BObject = BsonDocument
  ...
  
  // add new types
  type BObjectId = BsonObjectId
  type BInt32 = BsonInt32
  type BInt64 = BsonInt64
  ...
  
  // add new conversion functions, possibly following the existing interface
  def fromObjectId(oid: BObjectId): ObjectId = ...
  def makeObjectId(oid: ObjectId): BObjectId = ...
  def getObjectId(value: BValue): BObjectId = ...
  
  def fromInt32(n: BInt32): Int = ...
  ...
  
  def fromInt64(n: BInt64): Long = ...
  ...
}

// define a backend component
trait BsonBackendComponent extends BackendComponent {
  override val backend = BsonBackend
}

// define a trait with custom serializers
trait BsonBackendSerializers {
  // it should depend on the concrete BsonBackendComponent, not on generic BackendComponent
  this: BsonBackendComponent with TypesComponent =>
  
  import backend._
  
  // and here we can use all custom functions defined in the custom backend
  
  implicit val objectIdReadWriter: ReadWriter[ObjectId] = 
    ReadWriter.writing(backend.makeObjectId).reading {
      case bv: BObjectId => backend.fromObjectId(bv)
    }
    
  implicit val intReadWriter: ReadWriter[Int] =
    ReadWriter.writing(backend.makeInt32).reading {
      case bv: BInt32 => backend.fromInt32(bv)
    }
  
  ...
}

// finally, define the pickler trait by mixing it all together
trait BsonPickler extends DefaultPickler with BsonBackendComponent with BsonBackendSerializers
object BsonPickler extends BsonPickler
```

Note that picklers defined in the custom trait will have a greater priority than picklers inherited
from the `DefaultPickler` trait. Therefore, `intReadWriter` defined in the trait above
will be used instead of the `intReader`/`intWriter` pair defined in `PrimitiveReadWritersComponent`
which is inherited by `DefaultPickler`.

You can find an example of this technique in the [official BSON backend implementation][bson-backend].

  [bson-backend]: https://github.com/netvl/picopickle/blob/master/mongodb/src/main/scala/io/github/netvl/picopickle/backends/mongodb/bson.scala

### <a name="creating-custom-serializers"></a> Creating custom serializers

picopickle defines `Writer` and `Reader` basic types in `TypesComponent` which are called *serializers*. 
They are responsible for converting arbitrary types to their backend representation and back, respectively. 
The most basic way to construct custom serializers is to use `apply` method on `Reader` and `Writer` 
companion objects, which take `PartialFunction[backend.BValue, T]` and `T => backend.BValue`, 
respectively (you can find examples of both above).

(Terminology note: `Writer` and `Reader` are called *serializers*, while typed serialization objects described above,
that is, the ones returned by the call of `serializer[T]` method, are called *serializer objects*. While related,
these are different things. Serializer objects are completely optional, you won't have to use them if you don't want;
on the other hand, serializers are the key entities in picopickle and you can't do away with them.)

Any `Writer`, since it receives a total function, should be able to serialize any values of its corresponding type.
`Reader`, however, can fail to match the backend representation. See below for more information on error
handling in picopickle.

`TypesComponent` also defines a combined serializer called `ReadWriter`:

```scala
type ReadWriter[T] = Reader[T] with Writer[T]
```

Its companion object also provides convenient facilities to create its instances. The example above can be
rewritten with `ReadWriter` like this:

```scala
implicit val definedByIntReadWriter: ReadWriter[DefinedByInt] = ReadWriter.reading {
  case backend.Extract.Number(x) => DefinedByInt(x.intValue(), x.intValue().toString)
}.writing {
  case DefinedByInt(x, _) => backend.makeNumber(x)
}
```

You can switch `reading`/`writing` branches order if you like.

### <a name="extractors-and-backend-conversion-implicits"></a> Extractors and backend conversion implicits

`Backend` trait provides methods to create and deconstruct objects of backend representation: these are `make*`,
`from*` and `get*` methods described above. To simplify writing custom serializers, however, picopickle
provides a set of tools which help you writing conversions. The most basic of them are *extractors* and
*backend conversion implicits*.

Backend object contains several singleton objects with `unapply` methods which can be used to pattern-match
on `backend.BValue` and obtain the low-level values out of it, for example, to get a `Map[String, backend.BValue]`
out of `backend.BObject`, if this particular `backend.BValue` which you're matching on indeed is a `backend.BObject`:

```scala
backend.makeObject(...) match {
  case backend.Extract.Object(m) =>  // m is of type Map[String, backend.BValue]
}
```

There are extractors for all of the main backend representation variants:
 * `backend.Extract.Object`
 * `backend.Extract.Array`
 * `backend.Extract.String`
 * `backend.Extract.Number`
 * `backend.Extract.Boolean`

Their `unapply` implementation simply calls corresponding `get*` and `from*` methods, like this:

```scala
object Extractors {
  object String {
    def unapply(value: BValue): Option[String] = getString(value).map(fromString)
  }
}
```

The opposite conversion (from primitives to the backend representation) can be done with `make*` methods on the
backend, but picopickle also provides a set of implicit decorators which provide `toBackend` method on all of
the basic types. These decorators are defined in `backend.conversionImplicits` object:

```scala
import backend.conversionImplicits._
 
val s: backend.BString = "hello world".toBackend
 
// the above is equivalent to this:
 
val s: backend.BString = backend.makeString("hello world")
```

These implicit methods are somewhat more convenient than `make*` functions.

<a name="converters"></a> Converters
------------------------------------

Low-level conversions, however, may be overly verbose to write. picopickle provides a declarative way of
defining how the backend representation should be translated to the desired Scala objects and vice versa.
This is done with *converters*.

A converter looks much like a `ReadWriter`; however, it is parameterized by two types, source and target:

```scala
trait Converter[-T, +U] {
  def toBackend(v: T): backend.BValue
  def isDefinedAt(bv: backend.BValue): Boolean
  def fromBackend(bv: backend.BValue): U
}
```

The converters library defines several implicit conversions which allow any converter to be used as the
corresponding `Reader`, `Writer` or `ReadWriter`:

```scala
Converter[T, _] -> Writer[T]
Converter[_, U] -> Reader[U]
Converter[T, T] -> ReadWriter[T]
```

A converter which consumes and produces the same type is called an *identity* converter for that type. Naturally,
only identity converters can be used as `ReadWriter`s. Identity converters have a convenient type alias
`Converter.Id[T]`.

Converters library also defines several combinators on converters which allow combining them to obtain new
converters, and it also provides built-in converters for basic primitive types and objects and arrays.

For example, here is how you can define a conversion for some case class manually:

```scala
case class A(a: Boolean, b: Double)
 
trait CustomSerializers extends JsonPickler {
  import shapeless._
  import converters._
 
  val aConverter: Converter.Id[A] = unlift(A.unapply) >>> obj {
    "a" -> bool ::
    "b" -> num.double ::
    HNil
  } >>> A.apply _
 
  val aReadWriter: ReadWriter[A] = aConverter  // an implicit conversion is used here
}
```

Here `obj.apply` is used to define an identity converter for `Boolean :: Double :: HNil`,
and `>>>` operations "prepend" and "append" a deconstructor and a constructor for class `A`:

```scala
A.unapply          : A => Option[(Boolean, Double)]
unlift(A.unapply)  : A => (Boolean, Double)
 
A.apply _          : (Boolean, Double) => A
 
obj {
  "a" -> bool ::
  "b" -> num.double ::
  HNil
}                  : Converter.Id[Boolean :: Double :: HNil]
```

`bool` and `num.double` are identity converters for `Boolean` and `Double`, respectively.

`>>>` operations employ a little of shapeless magic to convert the functions like the ones above to functions
which consume and produce `HList`s. There is also `>>` combinator which does not use shapeless and "prepends"
and "appends" a function of corresponding type directly:

```scala
(A => B) >> Converter[B, C] >> (C => D)  ->  Converter[A, D]
 
// compare:
 
(A => (T1, T2, ..., Tn)) >>> Converter.Id[T1 :: T2 :: ... :: Tn :: HNil] >>> ((T1, T2, ..., Tn) => A)  ->  Converter.Id[A]
```

Note that this is very type-safe. For example, if you get the order or the types of fields in `obj` wrong, it won't compile.

picopickle additionally provides a convenient implicit alias for `andThen` on functions, also called `>>`. Together with 
`>>`  on converters this allows writing chains of transformations easily. For example, suppose you have an object which 
can be represented as an array of bytes. Then you want to serialize this byte array as a string in Base64 encoding. 
This can be written as follows:

```scala
import java.util.Base64
import java.nio.charset.StandardCharsets
 
case class Data(s: String)
object Data {
  def asBytes(d: Data) = d.s.getBytes(StandardCharsets.UTF_8)
  def fromBytes(b: Array[Byte]) = Data(new String(b, StandardCharsets.UTF_8))
}
 
val dataReadWriter: ReadWriter[Data] =
  Data.asBytes _ >>
  Base64.getEncoder.encodeToString _ >>
  str >>
  Base64.getDecoder.decode _ >>
  Data.fromBytes _
```

The sequence of functions chained with `>>` naturally defines the transformation order in both directions.

Similar thing is also possible for arrays. For example, you can serialize your case class as an array
of fields:

```scala
val aReadWriter: ReadWriter[A] = unlift(A.unapply) >>> arr(bool :: num.double :: HNil) >>> A.apply _
```

Naturally, there are converters for homogeneous arrays and objects too - they allow mapping to Scala collections:

```scala
val intListConv: Converter.Id[List[Int]] = arr.as[List].of(num.int)
val vecTreeMapConv: Converter.Id[TreeMap[String, Vector[Double]]] = obj.as[TreeMap].to(arr.as[Vector].of(num.double))
```

There is also a converter which delegates to `Reader` and `Writer` if corresponding implicit instances are available:

```scala
val optionStringConv: Converter.Id[Option[String]] = value[Option[String]]
```

You can find more on converters in their Scaladoc section (**TODO**).

<a name="supported-types"></a> Supported types
----------------------------------------------

By default picopickle provides a lot of serializers for various types which do their
best to represent their respective types in the serialized form as close as possible.
These serializers are then mixed into a single pickler.

The serializers are defined in a couple of traits:

```
io.github.netvl.picopickle.{CollectionReaders, CollectionWriters, CollectionReaderWritersComponent}
io.github.netvl.picopickle.{ShapelessReaders, ShapelessWriters, ShapelessReaderWritersComponent}
io.github.netvl.picopickle.{PrimitiveReaders, PrimitiveWriters, PrimitiveReaderWritersComponent}
io.github.netvl.picopickle.{TupleReaders, TupleWriters, TupleReaderWritersComponent}  // generated automatically
```

Every serializer is an overloadable `def` or `val`, so you can easily customize serialization
format by overriding the corresponding implicit definition with your own one.

Examples below use `JsonPickler`, so it is implicitly assumed that something like

```scala
import io.github.netvl.picopickle.backends.jawn.JsonPickler._
```

is present in the code.

### <a name="primitives-and-basic-types"></a> Primitives and basic types

picopickle natively supports serialization of all primitive and basic types:

```scala
writeString(1: Int)       shouldEqual "1"
writeString(2L: Long)     shouldEqual "2"
writeString(12.2: Double) shouldEqual "3"
writeString('a')          shouldEqual "\"a\""
writeString("hello")      shouldEqual "\"hello\""
writeString(true)         shouldEqual "true"
writeString(false)        shouldEqual "false"
writeString(null)         shouldEqual "null"
writeString('symbol)      shouldEqual "\"symbol\""
```

By default characters are serialized as strings, but, for example, collections backend redefines this behavior.

picopickle also can serialize `Option[T]` and `Either[L, R]` as long as there are serializers for their type
parameters:

```scala
writeString(Some(1)) shouldEqual "[1]"
writeString(None)    shouldEqual "1"

writeString(Left("hello"))   shouldEqual """[0,"hello"]"""
writeString(Right('goodbye)) shouldEqual """[1,"goodbye"]"""
```

Optional values are also handled specially when they are a part of case class definition; see below for more
explanation.

Please note that `Either[L, R]` serialization format is not final and can change in future versions.

### <a name="numbers-and-accuracy"></a> Numbers and accuracy

Most JSON libraries represent numbers as 64-bit floats, i.e. `Double`s, but some numerical values do not fit into
`Double`, and rounding occurs:

```scala
80000000000000000.0 shouldEqual 80000000000000008.0   // does not throw
```

In order to represent numbers as accurately as possible picopickle by default serializes all `Long`s which
cannot be represented as `Double` precisely as strings:

```scala
writeString(80000000000000000L)      shouldEqual "\"80000000000000000\""
writeString(Double.PositiveInfinity) shouldEqual "Infinity"
```

The same mechanism will probably be used when `BigInt`/`BigDecimal` handlers will be added.

In some backends, however, this behavior can be overridden, as is done, for example, in the 
official BSON backend.

### <a name="tuples"></a> Tuples

Tuples are serialized as arrays:

```scala
writeString((1, true, "a"))  shouldEqual "[1,true,\"a\"]"
```

The only exception is a tuple of zero items, usually called `Unit`. It is serialized as an empty object:

```scala
writeString(())           shouldEqual "{}"
```

Naturally, all elements of tuples must be serializable as well.

Tuple serializer instances are generated as a part of build process, and currently only
tuples with the length up to and including 22 are supported.

### <a name="collections"></a> Collections

Most of Scala collections library classes are supported, including all of the abstract ones below the `Iterable`,
as well as arrays:

```scala
writeString(Iterable(1, 2, 3, 4))    shouldEqual "[1,2,3,4]"
writeString(Seq(1, 2, 3, 4))         shouldEqual "[1,2,3,4]"
writeString(Set(1, 2, 3, 4))         shouldEqual "[1,2,3,4]"
writeString(Map(1 -> 2, 3 -> 4))     shouldEqual "[[1,2],[3,4]]"

writeString(1 :: 2 :: 3 :: Nil)      shouldEqual "[1,2,3]"
writeString(Vector(1, 2, 3))         shouldEqual "[1,2,3]"
wrtieString(TreeMap(1 -> 2, 3 -> 4)) shouldEqual "[[1,2],[3,4]]"

writeString(Array(1, 2, 3))          shouldEqual "[1,2,3]"
```

Mutable collections can be [de]serialized as well.

Maps are serialized like iterables of two-element tuples, that is, into arrays of two-element arrays. However,
if the map has string keys (which is determined statically), it will be serialized as an object:

```scala
writeString(Map("a" -> 1, "b" -> 2)) shouldEqual """{"a":1,"b":2}"""
```

The above behavior of serializing maps with string keys is the default, but it can be extended. See below.

If you're using abstract collection types like `Seq`, `Set` or `Map`, picopickle will work flawlessly. If you
use concrete collection types, however, there could be problems. picopickle has a lot of instances for most of
the main concrete implementations, but not for all of them. If you need something which is not present in the
library, feel free to file an issue.

### <a name="map-serialization-with-non-string-keys"></a> Map serialization with non-string keys

JSON-like languages usually don't allow using non-string values as object keys, and picopickle enforces this
restriction by its `BObject` representation which requires string keys. However, this is sometimes overly restrictive,
especially in a richly typed language like Scala and because of common patterns which follow from this.

It is not unusual for Scala projects to have a newtype or several for `String`, for example, for different
kind of identifiers:

```scala
case class PostId(id: String)
case class UserId(id: String)
```

Alternatively, it is possible to have such simple value class which does not wrap a `String` but which can easily
be converted to and from a string:

```scala
case class EntityPath(elems: Vector[String]) {
  override def toString = elems.mkString("/")
}
object EntityPath {
  def fromString(s: String) = EntityPath(s.split("/").toVector)
}
```

It is sometimes desirable to have these classes as keys in maps:

```scala
type UserMap = Map[UserId, User]
type EntityLocations = Map[EntityPath, Entity]
```

One would naturally want for these maps to have an object-based representation (instead of an array of arrays)
because keys are easily converted to and from strings. In picopickle, however, only maps of type `Map[String, T]`
can be directly serialized as objects.

To allow this kind of pattern, picopickle provides a way to define custom converters for map keys. When a map
with keys of type `T` is serialized or deserialized, and if there is an instance of type 
`ObjectKeyReader[T]`/`ObjectKeyWriter[T]`/`ObjectKeyReadWriter[T]` in scope, then it will be used to obtain
a `String` from `T` (or vice versa) which will then be used as an object key:

```scala
implicit val userIdObjectKeyReadWriter = ObjectKeyReadWriter(_.id, UserId)

// below a `_.toString` conversion is implicitly used
implicit val entityPathObjectKeyReadWriter = ObjectKeyReadWriter(EntityPath.fromString)

write[UserMap](Map(UserId("u1") -> ..., UserId("u2") -> ...)) shouldEqual 
  Map("u1" -> ..., "u2" -> ...)
  
write[EntityLocations](Map(EntityPath(Vector("a", "b")) -> ..., EntityPath(Vector("a", "c")) -> ...)) shouldEqual
  Map("a/b" -> ..., "a/c" -> ...)

// reading works just as well
```

However, with this flexibility in large codebases where one pickler is shared by lots of different classes it is easy 
to accidentally add a conversion which would break serialization format in some other part of the project. To
allow controlling this picopickle supports *disabling* of automatic map serialization for unknown key types.
You would need then to define an object key serializer for this particular type or explicitly allow maps with
this type as a key to be serialized as an array of arrays. You need to create a custom pickler and mix 
`MapPicklingDisabledByDefault` trait into it:

```scala
object CustomPickler extends CollectionsPickler with MapPicklingDisabledByDefault

// won't compile because there is no ObjectKeyWriter[Int] in scope and serialization of maps
// with Int keys is not allowed
write(Map(1 -> "a", 2 -> "b"))  

// ---

object CustomPickler extends CollectionsPickler with MapPicklingDisabledByDefault {
  implicit val intObjectKeyReadWriter = ObjectKeyReadWriter(_.toInt)
}

// works because we have defined an object key serializer for Int
write(Map(1 -> "a", 2 -> "b")) shouldEqual Map("1" -> "a", "2" -> "b")

// ---

object CustomPickler extends CollectionsPickler with MapPicklingDisabledByDefault {
  implicit val intObjectKeyAllowed = allowMapPicklingWithKeyOfType[Int]
}

// works because we explicitly allowed maps of type Map[Int, T] to be serialized as an array of arrays
write(Map(1 -> "a", 2 -> "b")) shouldEqual Vector(Vector(1, "a"), Vector(1, "b"))
```

Note that currently even if map pickling is allowed like in the above piece of code, putting an object key serializer
for the corresponding type will force picopickle to use it, allowing potential unexpected changes of
serialization format like described above. However: first, this will be fixed in future versions; second,
it still disallows one to *accidentally* serialize maps as arrays of arrays and then have broken format
by deliberate introduction of keys serializer, which looks like the most likely possibility of introducing
such breaking changes.

### <a name="sealed-trait-hierarchies"></a> Sealed trait hierarchies

picopickle supports automatic serialization of sealed trait hierarchies (STH), that is, case classes, probably
inheriting a sealed trait. In other words, picopickle can serialize algebraic data types.

The most trivial examples of STH are standalone case objects and case classes:

```scala
case object A
case class B(x: Int, y: A)

writeString(A)        shouldEqual "{}"
writeString(B(10, A)) shouldEqual """{"x":10,"y":{}}"""
```

By default picopickle serializes case classes as objects with keys being the names of the fields. Case objects
are serialized as empty objects.

Case classes and objects can have a sealed trait as their parent:

```scala
sealed trait Root
case object A extends Root
case class B(x: Int, y: Boolean)
case class C(name: String, y: Root) extends Root
```

When you explicitly set the serialized type to `Root` (or pass a value of type `Root` but not of some concrete
subclass), it will be serialized as an object with a *discriminator key*:

```scala
writeString[Root](A)           shouldEqual """{"$variant":"A"}"""
writeString[Root](B(10, true)) shouldEqual """{"$variant":"B","x":10,"y":true}"""
writeString[Root](C("me", A))  shouldEqual """{"$variant":"C","name":"me","y":{"$variant":"A"}}"""
```

If you don't request `Root` explicitly, the classes will be serialized as if they were not a part of an STH:

```scala
writeString(B(10, true)) shouldEqual """{"x":10,"y":true}"""
```

Usually this is not a problem, however, because if you are working with a sealed trait, you usually have variables
of its type, not of its subtypes.

Sealed trait hierarchies serialization is implemented using shapeless `LabelledGeneric` implicitly materialized
instances and a bit of custom macros which handle field renaming and default values (both are not supported by 
shapeless natively).

### <a name="changing-the-discriminator-key"></a> Changing the discriminator key

You can customize the discriminator key used by shapeless serializers by overriding
`discriminatorKey` field defined in `io.github.netvl.picopickle.SealedTraitDiscriminator` trait
(its default value is `"$variant"`):

```scala
object CustomPickler extends JsonPickler {
  override val discriminatorKey = "$type"
}

// STH is from the example above
CustomPickler.writeString[Root](A) shouldEqual """{"$type":"A"}"""
```

Of course, you can extract it into a separate trait and mix it into different picklers if you want.

Alternatively, since 0.2.0 you can specify the discriminator key for the specific sealed trait
hierarchy by putting a `@discriminator` annotation on the sealed trait:

```scala
import io.github.netvl.picopickle.discriminator

@discriminator("status") sealed trait Root
case object Stopped extends Root
case class Running(name: String) extends Root

writeString[Root](Stopped)       shouldEqual """{status:"Stopped"}"""
writeString[Root](Running("me")) shouldEqual """{status:"Running","name":"me"}"""
```

If `@discriminator` annotation is present, then its value will be used as discriminator key;
otherwise, the default value from `discriminatorKey` pickler field will be used.

### <a name="serialization-of-optional-fields"></a> Serialization of optional fields

If a case class has a field of type `Option[T]`, then this field is serialized in a different way than
a regular option: if the value of the field is `None`, then the corresponding key will be absent from the serialized
data, and if it is `Some(x)`, then the key will be present and its value will be just `x`, without an additional
layer of an array:

```scala
case class A(name: String, x: Option[Long])

writeString(A("absent"))            shouldEqual """{"name":"absent"}"""
writeString(A("present", Some(42L)) shouldEqual """{"name":"present","x":42}"""
```

This allows easy evolution of your data structures - you can always add an `Option`al field and the data serialized
before this update will still be deserialized correctly, putting a `None` into the new field.

If an optional field again contains an option:

```scala
case class A(x: Option[Option[Long]])
```

then the "outer" option is serialized as described in the above paragraph while the "inner" option is serialized
as a possibly empty array, just like options are serialized in other contexts:

```scala
writeString(A(None))            shouldEqual """{}"""
writeString(A(Some(None)))      shouldEqual """{"x":[]}"""
writeString(A(Some(Some(10L)))) shouldEqual """{"x":[10]}"""
```

### <a name="renaming-fields-and-sealed-trait-variants"></a> Renaming fields and sealed trait variants

picopickle also provides an ability to rename fields and STH variant labels. This can be done by annotating
fields with `@key` annotation:

```scala
import io.github.netvl.picopickle.key

sealed trait Root
@key("0") case object A
@key("1") case class B(@key("a") x: Int, @key("b") y: Boolean)

writeString[Root](A)            shouldEqual """{"$variant":"0"}"""
writeString[Root](B(10, false)) shouldEqual """{"$variant":"1","a":10,"b":false}"""
```

Keys always are strings, though.

### <a name="default-values-of-case-class-fields"></a> Default values of case class fields

picopickle also respects default values defined in a case class, which simplifies changes in your data classes
even more. If a field has a default value and the serialized object does not contain the corresponding field,
the default value will be used:

```scala
case class A(n: Int = 11)

readString[A]("""{"n":22}""") shouldEqual A(22)
readString[A]("""{}""")       shouldEqual A()
```

As you can see, this mechanism naturally interferes with the optional fields handling. picopickle resolves 
this conflict in the following way: if no value is present at the corresponding key and a default value is 
set for the field, then it takes precedence over option handling. This affects a rather rare case when there 
is an optional field with a default value other than `None`:

```scala
case class A(n: Option[Int] = Some(10))

readString[A]("{}") shouldEqual A(Some(10))  // not A(None)
```

This is what usually expected in such situation.

### <a name="varargs"></a> Varargs

Since version 0.2.0 picopickle supports reading and writing case classes with variable arguments. All of
the arguments passed to such case class will be serialized as an array:

```scala
case class A(x: Int*)

writeString(A(1, 2, 3)) shouldEqual """{"x":[1,2,3]}"""
```

Naturally, all elements of this array are serialized with their respective serializers.

### <a name="nulls"></a> Nulls

`null` value, as is widely known, tends to cause problems, and it is discouraged in idiomatic Scala code.
Unfortunately, sometimes you need to interact with external systems which do use nulls. JSON has null value as well.
Because of this picopickle supports nulls (it even has `BNull` as one of the fundamental backend types) but
it also provides means to control how nulls should be handled.

`Reader` and `Writer` traits do not contain any special logic to handle nulls. Instances of `Reader` and `Writer`
created through their companion objects, however, do have such logic: they delegate null handling to a `NullHandler`
instance provided by `NullHandlerComponent`. `NullHandler` is a trait of the following structure:

```scala
trait NullHandler {
  def handlesNull: Boolean
  def toBackend[T](value: T, cont: T => backend.BValue): backend.BValue
  def fromBackend[T](value: backend.BValue, cont: backend.BValue => T): T
}
```

That is, it is a kind of a preprocessor which inspects the passed value for nulls and can [de]serialize them
specially or prohibit the [de]serialization at all.

By default picopickle allows nulls everywhere (`DefaultPickler` includes `DefaultNullHandlerComponent`). That is,
if a null is serialized, it will be represented unconditionally with `backend.BNull`, and `backend.BNull` will
be deserialized (again, unconditionally) as a `null`.

There is another `NullHandlerComponent` implementation, namely `ProhibitiveNullHandlerComponent`, which disallows
serialization of nulls, throwing an exception if it encounters a null value either in Scala object or in a
backend object. If you don't need to keep compatibility with some external system which uses null values then 
it may be sensible to extend the desired pickler, overriding the default null handler:

```scala
trait MyJsonPickler extends JsonPickler with ProhibitiveNullHandlerComponent
```

As long as you use `Reader`/`Writer` companion objects or converters to create your custom serializers,
the null handling behavior will be consistent for all types handled by your pickler.

### <a name="accurate-numbers-serialization"></a> Accurate numbers serialization

Some backends do not allow serializing some numbers accurately. For example, most JSON implementations
represent all numbers with 64-bit floating point numbers, i.e. `Double`s. Scala `Long`, for example,
can't be represented accurately with `Double` in its entirety. This is even more true for big integers and decimals.

picopickle backends provide means to serialize arbitrary numbers as accurately as possible with these methods:

```scala
def makeNumberAccurately(n: Number): BValue
def fromNumberAccurately(value: BValue): Number
```

You can see that these methods take and return `BValue` instead of `BNumber`. Backend implementations can take
advantage of this and serialize long numbers as strings or in some other format in order to keep the precision.
Built-in serializers for numbers use these methods by default.

picopickle also provides a special trait, `DoubleOrStringNumberRepr`, which provides methods to store a number
as a `BNumber` if it can be represented precisely in `Double` as a `BString` otherwise.
This trait is useful e.g. when writing a JSON-based backend.

### <a name="value-classes"></a> Value classes

With picopickle, you can opt-in to serialize value classes (i.e. the ones extending the `AnyVal` class) directly
as values, bypassing the usual map representation of objects. To enable this behavior, extend your pickler with
`ValueClassReaderWritersComponent`:

```scala
trait MyJsonPickler extends JsonPickler with ValueClassReaderWritersComponent
import MyJsonPickler._

class A(val x: Int) extends AnyVal
writeString(A(10)) shouldEqual "10"  // not """{"x":10}"""
```

<a name="official-backends"></a> Official backends
--------------------------------------------------

### <a name="collections-pickler"></a> Collections pickler

picopickle has several "official" backends. One of them, provided by `picopickle-core` library, allows serialization
into a tree of collections. This backend is available immediately with only the `core` dependency:

```scala
libraryDependencies += "io.github.netvl.picopickle" %% "picopickle-core" % "0.3.0"
```

In this backend the following AST mapping holds:

```
BValue   -> Any
BObject  -> Map[String, Any]
BArray   -> Vector[Any]
BString  -> String
BNumber  -> Number
BBoolean -> Boolean
BNull    -> Null
```

In this backend the backend representation coincide with the target media, so no conversion methods except the
basic `read`/`write` are necessary.

This backend also tweaks the default `Char` serializer to write and read characters as `Char`s, not
as `String`s (which is the default behavior).

Note that everything else, even other collections, are still serialized as usual, so, for example, tuples are
represented as vectors and maps are represented as vectors of vectors:

```scala
write((2: Int, "abcde": String))  ->  Vector(2, "abcde")
write(Map(1 -> 2, 3 -> 4))        ->  Vector(Vector(1, 2), Vector(3, 4))
```

Collections pickler also do not use accurate number serialization because its backend representation is already
as accurate as possible.

### <a name="json-pickler"></a> JSON pickler

Another official backend is used for conversion to and from JSON. JSON parsing is done with [jawn] library;
JSON rendering, however, is custom. This backend is available in `picopickle-backend-jawn`:

```scala
libraryDependencies += "io.github.netvl.picopickle" %% "picopickle-backend-jawn" % "0.3.0"
```

This backend's AST is defined in `io.github.netvl.picopickle.backends.jawn.JsonAst` and consists of several
basic case classes corresponding to JSON basic types. No additional utilities for JSON manipulation are provided;
you should use another library if you want this.

JSON backend additionally provides two sets of methods: `readAst`/`writeAst`, which convert JSON AST from and to the
JSON rendered as a string, and `readString`/`writeString`, which [de]serialize directly from and to a string.
Usually the last pair of methods is what you want to use when you want to work with JSON serialization.

No support for streaming serialization is available and is not likely to appear in the future because of the
abstract nature of backends (not every backend support streaming, for example, collections backend doesn't) and
because it would require a completely different architecture.

### <a name="bson-pickler"></a> BSON pickler

Another official backend is used for conversion to and from BSON AST, as defined by [MongoDB BSON][bson] library.

```scala
libraryDependencies += "io.github.netvl.picopickle" %% "picopickle-backend-mongodb-bson" % "0.3.0"
```

In this backend the following AST mapping holds:

```
BValue   -> BsonValue
BObject  -> BsonDocument
BArray   -> BsonArray
BString  -> BsonString
BNumber  -> BsonNumber
BBoolean -> BsonBoolean
BNull    -> BsonNull
```

BSON backend also defines additional types as follows:

```
BObjectId -> BsonObjectId
BInt32    -> BsonInt32
BInt64    -> BsonInt64
BDouble   -> BsonDouble
BDateTime -> BsonDateTime
BBinary   -> BsonBinary
BSymbol   -> BsonSymbol
```

Additional functions for conversion from Scala core types to these types are available in the
backend:

```scala
  def fromBinary(bin: BBinary): Array[Byte]
  def makeBinary(arr: Array[Byte]): BBinary
  def getBinary(value: BValue): Option[BBinary]
  
  def fromObjectId(oid: BObjectId): ObjectId
  def makeObjectId(oid: ObjectId): BObjectId
  def getObjectId(value: BValue): Option[BObjectId]
  
  def fromDateTime(dt: BDateTime): Long
  def makeDateTime(n: Long): BDateTime
  def getDateTime(value: BValue): Option[BDateTime]

  def fromSymbol(sym: BSymbol): Symbol
  def makeSymbol(sym: Symbol): BSymbol
  def getSymbol(value: BValue): Option[BSymbol]

  def fromInt32(n: BInt32): Int
  def makeInt32(n: Int): BInt32
  def getInt32(value: BValue): Option[BsonInt32]

  def fromInt64(n: BInt64): Long
  def makeInt64(n: Long): BInt64
  def getInt64(value: BValue): Option[BsonInt64]

  def fromDouble(n: BDouble): Double
  def makeDouble(n: Double): BDouble
  def getDouble(value: BValue): Option[BsonDouble]
```

Corresponding extractors are available in `backend.BsonExtract` object, and backend conversion
implicits are defined in `backend.bsonConversionImplicits`:

```scala
  Reader {
    case backend.BsonExtract.ObjectId(oid) =>
      // oid: backend.BObjectId == org.bson.BsonObjectId
  }
  
  import backend.bsonConversionImplicits._
  
  val bin: backend.BBinary = Array[Byte](1, 2, 3).toBackend
```

This backend overrides numerical readers and writers to serialize Scala numbers to the smallest
type possible, i.e. `Byte`, `Short` and `Int` are serialized as `BInt32`, `Long` is serialized
as `BInt64`, and `Float` and `Double` are serialized as `BDouble`. You can see that in this backend 
there is no need to use additional measures to serialize numbers accurately.

This backend also provides serializers for `Array[Byte]`, `Symbol`, `Date` and `ObjectId` types
which are serialized as `BBinary`, `BSymbol`, `BDateTime` and `BObjectId`, respectively.

And finally, this backend provides identity serializers for all `BValue` children types, that is,
it serializes `BValue` as `BValue`, `BString` as `BString`, `BInt64` as `BInt64` and so on.

  [bson]: http://mongodb.github.io/mongo-java-driver/3.0/bson/

<a name="error-handling"></a> Error handling
--------------------------------------------

While serialization is straightforward and should never fail (if it does, it is most likely a bug in the library
or in some `Writer` implementation), deserialization is prone to errors because the serialized representation usually
has free-form structure and is not statically mapped on its Scala representation.

picopickle has a special exception type which is thrown upon deserialization errors. This exception is defined
in `ExceptionsComponent` like this:

```scala
  case class ReadException(message: String, data: backend.BValue, cause: Throwable = null)
    extends BaseException(message, cause)

  object ReadException {
    def apply(reading: String, expected: String, got: backend.BValue): ReadException =
      ReadException(s"reading $reading, expected $expected, got $got", data = got)
  }
```

When deserialization of some type is attempted over a backend representation which is incompatible with
the requested type, for most of the built-in deserializers the exception will contain the message about
what was being read, what was expected and what was actually provided to the deserializer:

```scala
readString[Int]("""{"some":"thing"}""")
io.github.netvl.picopickle.ExceptionsComponent$ReadException: reading number, expected number or string containing a number, got JsonObject(Map(some -> JsonString(thing)))
```

You can participate in this exception handling with your own deserializers very easily. `Reader` and `ReadWriter`
has certain methods to create deserializers which allow you to use custom messages for errors:

```scala
case class A(name: String)

// Pre-defined message format, like above
Reader.reading[A] {
  case backend.Extract.String(s) => A(s)
}.orThrowing(whenReading = "A", expected = "string")

// Arbitrary custom message
Reader.reading[A] {
  case backend.Extract.String(s) => A(s)
}.orThrowing(v => s"Got $v instead of string when reading A")

// ReadWriters also can be customized
ReadWriter.writing[A](_.name.toBackend)
  .reading { case backend.Extract.String(s) => A(s) }
  .orThrowing(whenReading = "A", expected = "string")
  
// Works in any order
ReadWriter.reading[A] { case backend.Extract.String(s) => A(s) }
  .orThrowing(whenReading = "A", expected = "string")
  .writing(_.name.toBackend)
```

In readers constructed in the above form the error will be thrown when the partial function
used for reading is not defined on the incoming value. That is, the following reader
won't ever throw a `ReadException`:

```scala
Reader.reading[A] {
  case value => A(backend.fromString(value.asInstanceOf[BString]))
}.orThrowing(whenReading = "A", expected = "string")
```

It will throw a `ClassCastException` instead if something which is not a string is supplied.

If you still need to use a catch-all partial function for a reader, you can always throw a `ReadException`
yourself:

```scala
Reader[A] {
  case value => if (value.isInstanceOf[String]) A(backend.fromString(value.asInstanceOf[BString])
                else throw ReadException(reading = "A", expected = "string", got = value)
}
```

While the example above is absolutely contrived, there are legitimate use cases for it.

Additional backend implementations may inherit `ExceptionsComponent.BaseException` to implement custom
errors. For example, this is done in JSON backend to wrap a Jawn parse exception.

Finally, `Pickler` trait provides `tryRead()` method which returns `Try[T]` instead of `T` returned
by `read()`. This method never throws any exceptions and instead returns them as a `Failure` variant
of `Try[T]`. Serializer objects also have such methods, as well as official backends with custom 
serialization methods, like Jawn's `tryReadString()`.


<a name="limitations"></a> Limitations
--------------------------------------

picopickle does not support serializing `Any` in any form because it relies on the static knowledge of
types being serialized. However, its design, as far as I can tell, in principle does not disallow writing
a serializer for `Any` which would use reflection. This is not even in plans, however.

It also seems that trying to serialize sealed trait hierarchies where the sealed trait itself has a type parameter
causes the compiler to die horribly. Regular parameterized case classes work fine, however.

Object graphs with circular loops are not supported and will cause stack overflows. This is not usually a problem
because it is only possible to construct such graphs when at least a part of them is mutable (e.g. a `var` field
or a mutable collection) which is discouraged in idiomatic Scala code.

Due to limitations of how Scala reflection/macros work, it is better not to re-define serializers in the same
place as the serialized classes if these classes form a sealed trait hierarchy. For example, something like this
won't work:

```scala
object Serializers {
  import SomePickler._
  
  sealed trait Root
  case class A(x: Int) extends Root
  case object B extends Root
  
  implicit val rootReadWriter = ReadWriter[Root]
}
```

This won't compile because it is impossible to inspect the sealed trait hierarchy of `Root` at the point where
a `LabelledGeneric` is materialized here (in the implicit parameters of `ReadWriter[Root]` call). If you want to
pre-generate serializers for your classes, write them in another object:

```scala
object Classes {
  sealed trait Root
  case class A(x: Int) extends Root
  case object B extends Root
}

object Serializers {
  import SomePicker._
  import Classes._
  
  implicit val rootReadWriter = ReadWriter[Root]
}
```


<a name="plans"></a> Plans
--------------------------

* Consider adding support for more types
* Consider adding more converters (e.g. for tuples)
* Add proper support for error handling in conversions
* Add more tests
* Add more documentation


<a name="changelog"></a> Changelog
----------------------------------

### 0.3.0

* Updated scala to 2.11.8
* Added support for serializing value classes as values

### 0.2.1

* Updated shapeless to 2.3.0, macroparadise to 2.1.0, jawn to 0.8.4, bson to 3.2.2, scala to 2.10.6
* Switched to macro-compat instead of hand-written macro API for 2.10 and 2.11

### 0.2.0

* Updated shapeless to 2.2.3, jawn to 0.8.8, scala to 2.11.7
* Fixed support for varargs (consequence of shapeless update)
* Improved reader interface (added `readOrElse` method and changed existing code to depend on it)
* Added proper error handling (#2)
* Added new BSON-based backend (#6)
* Added support for changing STH discriminator key on per-STH basis (#7)

### 0.1.3

* Added serializer object feature (#5)
* Added support for serializing arbitrary types as map keys provided there is a converter (#4)

### 0.1.2

* Updated Scala 2.10 minor version (4 -> 5)

### 0.1.1

* Fixed handling of classes with overloaded `apply` method in companions (#1)

### 0.1.0

* More serializer instances
* Added generic handling for accurate numbers serialization
* Added collections backend
* Support for recursive types
* Added converters
* Improved API for custom serializers
* Added support for renaming fields and sealed trait variants
* Added support for default values in case classes
* Added proper support for nulls
* Added test generators
* Started adding tests

### 0.0.2

* Added more instances for primitive types
* Improved API

### 0.0.1

* Initial release
