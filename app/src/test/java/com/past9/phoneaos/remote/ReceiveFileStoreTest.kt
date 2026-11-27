package com.past9.phoneaos.remote
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
class ReceiveFileStoreTest {
 private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
 @Test fun resumeAndVerify() {
  val dir = Files.createTempDirectory("receive-test").toFile()
  val saved = mutableListOf<String>()
  val sink: (String, java.io.File) -> String = { _, f -> saved.add(f.readText()); "content://download/1" }
  val first = ReceiveFileStore(dir, sink).accept("id", 0, 2, "hello ".toByteArray(), "greeting.txt", 11, hash("hello world"))
  assertEquals("partial", first.status)
  assertEquals(1, first.nextIndex)
  assertEquals(1, ReceiveFileStore(dir, sink).accept("id", 0, 2, "hello ".toByteArray(), "greeting.txt", 11, hash("hello world")).nextIndex)
  val last = ReceiveFileStore(dir, sink).accept("id", 1, null, "world".toByteArray(), null, null, null)
  assertEquals("received", last.status)
  assertEquals(11L, last.bytes)
  assertEquals("content://download/1", last.path)
  assertEquals(listOf("hello world"), saved)
 }
 @Test fun mismatchNeverSaves() {
  val dir = Files.createTempDirectory("receive-test").toFile()
  var saved = false
  val result = ReceiveFileStore(dir) { _, _ -> saved = true; "x" }.accept("id", 0, 1, "bad".toByteArray(), "a.txt", 3, hash("good"))
  assertEquals("hash_mismatch", result.status)
  assertFalse(saved)
 }
 @Test fun outOfOrderReturnsNextIndex() {
  val store = ReceiveFileStore(Files.createTempDirectory("receive-test").toFile()) { _, _ -> "x" }
  store.accept("id", 0, 3, "a".toByteArray(), "a.txt", 3, hash("abc"))
  assertEquals(1, store.accept("id", 2, null, "c".toByteArray(), null, null, null).nextIndex)
 }
}
