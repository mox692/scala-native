import java.lang.ref.WeakReference

class MyWeakRef[A](a: A) extends WeakReference(a)
class MyWeakRef2[A](a: A) extends MyWeakRef(a)


@main def mainssssssssssssssssssssss =
  val refsaaaaaaaaaaaaaaaaa = new Array[MyWeakRef2[Array[Long]]](Int.MaxValue / 8)

  var i = 0
  while (i < refsaaaaaaaaaaaaaaaaa.length) {
    refsaaaaaaaaaaaaaaaaa(i) = new MyWeakRef2(new Array[Long](Int.MaxValue / 64))
    // if(refsaaaaaaaaaaaaaaaaa(i).isInstanceOf[WeakReference[_]])
    //   println("hhhhhhhhhhhhhhhhhhhhhhhhhh")
    var j = 0
    while (j < i) {
      if (refsaaaaaaaaaaaaaaaaa(j).get == null) {
        println("end4")
        println((i, j))
        System.exit(0)
      }
      j += 1
    }
    i += 1
  }

  // val mw = new MyWeakRef[Int](3)

  // if(mw.isInstanceOf[WeakReference[_]])
  //   println("mw is WeakReference")
  //   else
  //   println("mw is not WeakReference")

  // println(s"mw: ${mw}")
