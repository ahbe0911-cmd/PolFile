package com.polfile.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class LocalServer(private val context:Context, port:Int) {
 private val listener=ServerSocket(port)
 private val pool=Executors.newFixedThreadPool(4)
 @Volatile private var open=true
 fun start() {
  Thread {
   while(open) {
    try { val socket=listener.accept(); pool.execute { socket.use { serve(it) } } }
    catch(e:Exception) { if(!open) break }
   }
  }.apply { isDaemon=true;start() }
 }
 fun close() { open=false;listener.close();pool.shutdownNow() }
 private fun headers(input:InputStream):String {
  val buf=ByteArrayOutputStream();var state=0
  while(buf.size()<32768) {
   val b=input.read();if(b<0) throw EOFException()
   buf.write(b)
   state=when { state==0 && b==13 -> 1; state==1 && b==10 -> 2; state==2 && b==13 -> 3; state==3 && b==10 -> 4; b==13 -> 1; else -> 0 }
   if(state==4) return buf.toString("ISO-8859-1")
  }
  throw IOException("Request headers too long")
 }
 private fun reply(out:OutputStream,code:Int,body:String,type:String="text/plain; charset=utf-8") {
  val data=body.toByteArray(StandardCharsets.UTF_8)
  val reason=when(code) {200->"OK";201->"Created";400->"Bad Request";404->"Not Found";413->"Payload Too Large";415->"Unsupported Media Type";else->"Error"}
  out.write(("HTTP/1.1 "+code+" "+reason+"\r\nContent-Type: "+type+"\r\nContent-Length: "+data.size+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8))
  out.write(data);out.flush()
 }
 private fun escape(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;")
 private fun home(out:OutputStream) {
  val rows=FileStore.list(context).mapIndexed { index,file ->
   "<li><span>"+escape(file.name)+"<small>"+(if(file.size>=0) file.size.toString()+" bytes" else "")+"</small></span><a href='/d/"+index+"'>دریافت</a></li>"
  }.joinToString("")
  val html=context.assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }.replace("ROWS",rows)
  reply(out,200,html,"text/html; charset=utf-8")
 }
 private fun download(out:OutputStream,raw:String) {
  val index=raw.toIntOrNull() ?: -1
  val file=FileStore.list(context).getOrNull(index)
  if(file==null) {reply(out,404,"File not found");return}
  val stream=context.contentResolver.openInputStream(file.uri)
  if(stream==null) {reply(out,404,"File not accessible");return}
  stream.use {
   val filename=URLEncoder.encode(file.name,"UTF-8").replace("+","%20")
   val line="HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename*=UTF-8''"+filename+"\r\n"
   out.write((line+(if(file.size>=0) "Content-Length: "+file.size+"\r\n" else "")+"Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8))
   it.copyTo(out,64*1024);out.flush()
  }
 }
 private fun upload(path:String,headers:Map<String,String>,input:InputStream,out:OutputStream) {
  if(headers["content-type"]?.substringBefore(";")?.trim()?.lowercase()!="application/octet-stream") {reply(out,415,"Unsupported upload type");return}
  val length=headers["content-length"]?.toLongOrNull()
  if(length==null || length<0 || length>2147483647L) {reply(out,413,"Unsupported upload size (max 2 GB)");return}
  val raw=path.substringAfter("name=","").substringBefore("&")
  val name=URLDecoder.decode(raw,"UTF-8").replace('/','_').replace('\\','_').replace(Regex("[\u0000-\u001f\u007f]"),"_").trim().trim('.').take(120)
  if(name.isBlank()){reply(out,400,"Invalid filename");return}
  var uri:Uri?=null
  try {
   if(Build.VERSION.SDK_INT>=29) {
    val values=ContentValues().apply {
     put(MediaStore.Downloads.DISPLAY_NAME,name)
     put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream")
     put(MediaStore.Downloads.RELATIVE_PATH,"Download/PolFile")
     put(MediaStore.Downloads.IS_PENDING,1)
    }
    uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values) ?: throw IOException("Cannot create file")
   } else {
    val folder=File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"PolFile")
    folder.mkdirs(); uri=Uri.fromFile(File(folder,name))
   }
   val target=uri ?: throw IOException("No destination")
   val destination=if(target.scheme=="file") FileOutputStream(File(target.path!!)) else context.contentResolver.openOutputStream(target,"w") ?: throw IOException("No output")
   destination.use { stream ->
    val buf=ByteArray(65536);var remaining=length
    while(remaining>0) {
     val n=input.read(buf,0,minOf(remaining,buf.size.toLong()).toInt())
     if(n<0) throw EOFException("Incomplete upload")
     stream.write(buf,0,n);remaining-=n
    }
   }
   if(Build.VERSION.SDK_INT>=29) context.contentResolver.update(target,ContentValues().apply {put(MediaStore.Downloads.IS_PENDING,0)},null,null)
   FileStore.add(context,listOf(target))
   reply(out,201,"Uploaded")
  } catch(e:Exception) {
   uri?.let { if(it.scheme=="file") runCatching {File(it.path!!).delete()} else runCatching {context.contentResolver.delete(it,null,null)} }
   reply(out,500,"Upload failed")
  }
 }
 private fun serve(socket:Socket) {
  socket.soTimeout=120000
  try {
   val input=BufferedInputStream(socket.getInputStream())
   val output=BufferedOutputStream(socket.getOutputStream())
   val h=headers(input).split("\r\n")
   val first=h.firstOrNull()?.split(" ") ?: return
   if(first.size<2){reply(output,400,"Invalid request");return}
   val map=h.drop(1).filter {it.contains(":")}.associate {it.substringBefore(":").lowercase().trim() to it.substringAfter(":").trim()}
   val path=first[1].substringBefore("?")
   when {
    first[0]=="GET" && path=="/" -> home(output)
    first[0]=="GET" && path.startsWith("/d/") -> download(output,path.removePrefix("/d/"))
    first[0]=="POST" && path=="/upload" -> upload(first[1],map,input,output)
    else -> reply(output,404,"Not found")
   }
  } catch(_:Exception) {}
 }
}
