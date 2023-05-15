.. _faq:

FAQ
===

---

**Q:** How do I make the resulting executable smaller?

**A:** Compress the binary with https://upx.github.io/

---

**Q:** Does Scala Native support WebAssembly?

**A:** Support for WebAssembly is out of scope for the project.
If you need to run Scala code in the browser, consider using
`Scala.js <https://www.scala-js.org>`_ instead.

---

**Q:** How can I check the internal execution commands of LLVM?

**A:** By setting the log level to debug in the build.sbt file,
you can actually view the compile command in LLVM.

.. code-block:: scala

    lazy val sandbox =
      MultiScalaProject("sandbox", file("sandbox"))
        .enablePlugins(MyScalaNativePlugin)
        .withNativeCompilerPlugin
        .withJUnitPlugin
        .dependsOn(scalalib, testInterface % "test")
        .settings(
          logLevel := Level.Debug
        )

.. code-block:: text
[debug] Running
[debug] /usr/bin/clang
[debug] 	-c
[debug] 	/Users/s15255/work/scala-native/sandbox/.2.13/target/scala-2.13/native/scala/scalanative/unsigned.ll
[debug] /usr/bin/clang
[debug] 	-o
[debug] 	/Users/s15255/work/scala-native/sandbox/.2.13/target/scala-2.13/native/scala/scalanative/unsigned.ll.o
[debug] 	-Wno-override-module
[debug] 	-fexceptions
[debug] 	-funwind-tables
[debug] 	-fvisibility=hidden
[debug] 	-O0
[debug] 	-I/usr/local/include
[debug] 	-Qunused-arguments


Troubleshooting
---------------
When compiling your Scala Native project, the linker ``ld`` may fail with the following message:

::

  relocation R_X86_64_32 against `.rodata.str1.1' can not be used when making a shared object; recompile with -fPIC

It is likely that the ``LDFLAGS`` environment variable enables hardening. For example, this occurs when the ``hardening-wrapper`` package is installed on Arch Linux. It can be safely removed.

