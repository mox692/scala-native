// import java.lang.ref.WeakReference

// class MyWeakRef[A](a: A) extends WeakReference(a)

// @main def main =
//   val refs = new Array[WeakReference[Array[Long]]](Int.MaxValue / 8)

//   var i = 0
//   while (i < refs.length) {
//     refs(i) = new WeakReference(new Array[Long](Int.MaxValue / 64))
//     var j = 0
//     while (j < i) {
//       if (refs(j).get == null) {
//         println((i, j))
//         System.exit(0)
//       }
//       j += 1
//     }
//     i += 1
//   }

class Foo() {
  var name: String = ""
  var age: Int = 3
}

object Test {
  def main(args: Array[String]) = {
    val a = new Foo()
    println(a)
  }
}
