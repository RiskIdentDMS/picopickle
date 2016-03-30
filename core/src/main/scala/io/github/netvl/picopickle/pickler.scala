package io.github.netvl.picopickle

import scala.util.Try

trait Pickler {
  self: BackendComponent with TypesComponent =>
  def read[T: Reader](value: backend.BValue): T
  def write[T: Writer](value: T): backend.BValue

  def tryRead[T: Reader](value: backend.BValue): Try[T] = Try(read(value))

  class Serializer[T: Reader: Writer] {
    def read(value: backend.BValue): T = self.read(value)
    def tryRead(value: backend.BValue): Try[T] = self.tryRead(value)

    def write(value: T): backend.BValue = self.write(value)
  }
  def serializer[T: Reader: Writer] = new Serializer[T]
}

trait DefaultPickler
  extends Pickler
  with ExceptionsComponent
  with ShapelessReaderWritersComponent
  with DefaultValuesComponent
  with DefaultNullHandlerComponent
  with AnnotationSupportingSymbolicLabellingComponent
  with DefaultSealedTraitDiscriminatorComponent
  with PrimitiveReaderWritersComponent
  with CollectionReaderWritersComponent
  with ObjectKeyTypesComponent
  with ObjectKeyReaderWritersComponent
  with MapPicklingComponent
  with MapPicklingEnabledByDefault
  with TupleReaderWritersComponent
  with ConvertersComponent
  with TypesComponent
  with ValueClassesReaderWritersComponent {
  this: BackendComponent =>

  override def read[T](value: backend.BValue)(implicit r: Reader[T]): T = r.read(value)
  override def write[T](value: T)(implicit w: Writer[T]): backend.BValue = w.write(value)
}


