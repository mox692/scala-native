
* concurrentSkipListMap
* 6/12
  * ConcurrentSkipListMap classの private var fieldを移植中. その中でいろんなclassを移植する必要あり.
    * 手始めにKeySetから移植
      * 下記のようなエラーがあって、ちょくちょくgetterを消した
```
[error] /Users/s15255/work/scala-native/javalib/src/main/scala/java/util/concurrent/ConcurrentSkipListMap.scala:2325:16: method keySet is defined twice;
[error]   the conflicting variable keySet was defined at line 1197:15
[error]   override def keySet(): NavigableSet[K] = ???
```

   * それt同時に `keySet` とかを public fieldにした



## want
* SubMapKeyIteratorとかで、Keysetの???を消す
* ConcurrentSkipListMap のprivate valをコメントインしていく


## policy
* Object -> Any
* fieldやmethodを持たないものはcase classに.
