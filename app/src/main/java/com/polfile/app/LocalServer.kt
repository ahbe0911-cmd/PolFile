package com.polfile.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

object WebSession {
 @Volatile var key = randomKey()
 val client=MutableStateFlow("هنوز دستگاهی متصل نشده است")
 val events=MutableStateFlow<List<String>>(emptyList())
 private fun randomKey():String {
  val bytes=ByteArray(6);SecureRandom().nextBytes(bytes)
  return bytes.joinToString("") { "%02X".format(it.toInt() and 255) }
 }
 fun reset() {key=randomKey();client.value="هنوز دستگاهی متصل نشده است";events.value=emptyList()}
 fun log(event:String) {events.value=(listOf(event)+events.value).take(30)}
}

class LocalServer(private val context:Context, port:Int) {
 private val listener=ServerSocket()
 private val pool=Executors.newFixedThreadPool(6)
 @Volatile private var open=true
 init {
  listener.reuseAddress=true
  listener.bind(InetSocketAddress("0.0.0.0",port))
 }
 fun start() {
  Thread {
   while(open) {
    try { val socket=listener.accept();pool.execute {socket.use {serve(it)}} }
    catch(_:Exception) {if(!open) break}
   }
  }.apply {isDaemon=true;name="PolFile-server";start()}
 }
 fun close() {open=false;listener.close();pool.shutdownNow()}
 private fun requestHead(input:InputStream):String {
  val output=ByteArrayOutputStream();var state=0
  while(output.size()<32768) {
   val b=input.read();if(b<0) throw EOFException("Connection closed")
   output.write(b)
   state=when {state==0 && b==13->1;state==1 && b==10->2;state==2 && b==13->3;state==3 && b==10->4;b==13->1;else->0}
   if(state==4)return output.toString("ISO-8859-1")
  }
  throw IOException("Headers exceed 32KB")
 }
 private fun reply(out:OutputStream,code:Int,body:String,type:String="text/plain; charset=utf-8") {
  val bytes=body.toByteArray(StandardCharsets.UTF_8)
  val reason=when(code){200->"OK";201->"Created";400->"Bad Request";403->"Forbidden";404->"Not Found";413->"Payload Too Large";415->"Unsupported Media Type";else->"Internal Server Error"}
  out.write(("HTTP/1.1 "+code+" "+reason+"\r\nContent-Type: "+type+"\r\nContent-Length: "+bytes.size+"\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8))
  out.write(bytes);out.flush()
 }
 private fun html(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;")
 private fun fileId(uri:Uri):String=MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray()).take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
 private fun page(out:OutputStream) {
  val rows=FileStore.list(context).map {file ->
   val title=if(file.path.isNotEmpty())file.path else file.name
   val size=if(file.size>=0) file.size.toString()+" بایت" else "اندازه نامشخص"
   "<div class='row' data-name='"+html(title)+"'><div class='name'><strong>"+html(title)+"</strong><div class='small'>"+html(size)+"</div></div><a class='btn' href='/d/"+fileId(file.uri)+"?key="+WebSession.key+"'>↓ دریافت</a></div>"
  }.joinToString("")
  val template=context.assets.open("index.html").bufferedReader(StandardCharsets.UTF_8).use {it.readText()}
  reply(out,200,template.replace("ROWS",rows).replace("TOKEN_JSON","'"+WebSession.key+"'"),"text/html; charset=utf-8")
 }
 private fun download(out:OutputStream,id:String) {
  val file=FileStore.list(context).firstOrNull {fileId(it.uri)==id}
  if(file==null) {reply(out,404,"The file is not shared or its access has expired");return}
  val stream=context.contentResolver.openInputStream(file.uri)
  if(stream==null) {reply(out,404,"File is not readable");return}
  stream.use {
   val encoded=URLEncoder.encode(file.name,"UTF-8").replace("+","%20")
   out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename*=UTF-8''"+encoded+"\r\n"+
     (if(file.size>=0)"Content-Length: "+file.size+"\r\n" else "")+
     "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8))
   it.copyTo(out,64*1024)
   out.flush()
   WebSession.log("دریافت در کامپیوتر: "+file.name)
  }
 }
 private fun upload(name:String,headers:Map<String,String>,input:InputStream,out:OutputStream) {
  if(headers["content-type"]?.substringBefore(";")?.trim()?.lowercase()!="application/octet-stream") {
   reply(out,415,"Only raw file uploads are supported");return
  }
  val length=headers["content-length"]?.toLongOrNull()
  if(length==null || length<0 || length>2147483647L) {reply(out,413,"Maximum upload: 2 GB per file");return}
  val safe=name.replace('/','_').replace('\\','_').replace(Regex("[\u0000-\u001f\u007f]"),"_").trim().trim('.').take(120)
  if(safe.isBlank()){reply(out,400,"Invalid filename");return}
  var destination:Uri?=null
  try {
   if(Build.VERSION.SDK_INT>=29) {
    val v=ContentValues().apply {
     put(MediaStore.Downloads.DISPLAY_NAME,safe)
     put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream")
     put(MediaStore.Downloads.RELATIVE_PATH,"Download/PolFile")
     put(MediaStore.Downloads.IS_PENDING,1)
    }
    destination=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v) ?: throw IOException("Cannot create file")
   } else {
    val folder=File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"PolFile")
    if(!folder.exists()&&!folder.mkdirs())throw IOException("Cannot create folder")
    destination=Uri.fromFile(File(folder,safe))
   }
   val target=destination ?: throw IOException("No target")
   val output=if(target.scheme=="file") FileOutputStream(File(target.path!!)) else context.contentResolver.openOutputStream(target,"w") ?: throw IOException("Cannot open output")
   output.use {stream ->
    val buffer=ByteArray(65536);var remaining=length
    while(remaining>0) {
     val n=input.read(buffer,0,minOf(remaining,buffer.size.toLong()).toInt())
     if(n<0)throw EOFException("Incomplete upload")
     stream.write(buffer,0,n);remaining-=n
    }
   }
   if(Build.VERSION.SDK_INT>=29)context.contentResolver.update(target,ContentValues().apply {put(MediaStore.Downloads.IS_PENDING,0)},null,null)
   FileStore.add(context,listOf(target))
   WebSession.log("ارسال از کامپیوتر: "+safe)
   reply(out,201,"File saved in Download/PolFile")
  } catch(_:Exception) {
   destination?.let {uri-> if(uri.scheme=="file")runCatching {File(uri.path!!).delete()} else runCatching {context.contentResolver.delete(uri,null,null)}}
   reply(out,500,"Cannot save this file on the phone")
  }
 }
 private fun serve(socket:Socket) {
  try {
   socket.soTimeout=30000
   val input=BufferedInputStream(socket.getInputStream())
   val output=BufferedOutputStream(socket.getOutputStream())
   val head=requestHead(input).split("\r\n")
   val request=head.firstOrNull()?.split(" ") ?:return
   if(request.size<2){reply(output,400,"Invalid request");return}
   val url=request[1]
   val path=url.substringBefore("?")
   val query=url.substringAfter("?","").split("&").filter {it.contains("=")}
    .associate {URLDecoder.decode(it.substringBefore("="),"UTF-8") to URLDecoder.decode(it.substringAfter("="),"UTF-8")}
   if(path=="/" && query["key"]!=WebSession.key) {
    val landing="<!doctype html><html lang='fa' dir='rtl'><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'><title>پل فایل ـ اتصال</title>"+
      "<style>body{font-family:Tahoma,Arial;background:#fff3f1;color:#293249;margin:0}header{background:linear-gradient(120deg,#ffa69c,#fb6d73);color:white;padding:35px;text-align:center}main{max-width:460px;margin:-18px auto 25px;padding:25px;background:white;border-radius:23px;box-shadow:0 10px 30px #a8515020;text-align:center}h1{font-size:35px;margin:0}p{font-size:13px;line-height:2;color:#748095}input{width:90%;padding:15px;border:1px solid #eedddd;border-radius:13px;font-size:19px;text-align:center;letter-spacing:2px;direction:ltr}button{margin-top:15px;background:#f66b70;color:white;border:0;border-radius:13px;padding:14px 30px;font-size:15px;cursor:pointer}</style>"+
      "<header><h1>پل فایل</h1><div>گوشی و کامپیوتر، کنار هم</div></header><main><h2>اتصال به گوشی</h2><p>کد اتصال را از برنامه پل فایل در گوشی وارد کنید.<br>هر دو دستگاه باید به یک شبکه مشترک متصل باشند.</p>"+
      "<form action='/' method='get'><input name='key' maxlength='12' autocomplete='off' placeholder='کد ۱۲ رقمی' required><br><button type='submit'>اتصال و مشاهده فایل‌ها</button></form>"+
      "<p>بعد از اتصال، تب‌های «دریافت از گوشی» و «ارسال به گوشی» نمایش داده می‌شوند.</p></main></html>"
    reply(output,200,landing,"text/html; charset=utf-8")
    return
   }
   if(query["key"]!=WebSession.key){reply(output,403,"Invalid pairing code");return}
   val headers=head.drop(1).filter {it.contains(":")}.associate {it.substringBefore(":").trim().lowercase() to it.substringAfter(":").trim()}
   if(path!="/ping") WebSession.client.value=socket.inetAddress.hostAddress ?: "دستگاه متصل"
   socket.soTimeout=900000
   when {
    request[0]=="GET" && path=="/ping" -> reply(output,200,"OK")
    request[0]=="GET" && path=="/" -> page(output)
    request[0]=="GET" && path.startsWith("/d/") -> download(output,path.removePrefix("/d/"))
    request[0]=="POST" && path=="/upload" -> upload(query["name"].orEmpty(),headers,input,output)
    else -> reply(output,404,"Not found")
   }
  }catch(_:Exception){}
 }
}
