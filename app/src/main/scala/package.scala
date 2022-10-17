package wtf.nbd

package object obw {
  def runAnd[T](result: T)(action: Any): T = result
}
