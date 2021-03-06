/* Copyright 2009-2015 EPFL, Lausanne */

object Array8 {

  def foo(a: Array[Int]): Array[Int] = {
    require(a.length >= 2)
    a.updated(1, 3)
  } ensuring(res => res.length == a.length && res(1) == 3)

}
