package com.zbox.ai.core

/** Original bounds/coordinate policy. Unknown/zoomed metadata is rejected, never guessed by model name. */
object TouchFocusPolicy{
 data class Geometry(val width:Int,val height:Int,val afX:Int,val afY:Int,val afWidth:Int,val afHeight:Int)
 fun geometry(h:ByteArray,previewWidth:Int,previewHeight:Int):Geometry?{
  if(h.size<32 || previewWidth<=0 || previewHeight<=0)return null
  fun word(i:Int)=((h[i].toInt()and 255)shl 8)or(h[i+1].toInt()and 255)
  if(word(8)!=previewWidth || word(10)!=previewHeight)return null
  val w=word(16);val y=word(18)
  if(w !in 256..32768 || y !in 256..32768 || word(12)!=w || word(14)!=y)return null
  if(kotlin.math.abs(word(20)*2-w)>2 || kotlin.math.abs(word(22)*2-y)>2)return null
  if(kotlin.math.abs(w.toFloat()/y-previewWidth.toFloat()/previewHeight)>.08f)return null
  if(word(28)>=w || word(30)>=y || word(24)<=0 || word(26)<=0)return null
  return Geometry(w,y,word(28),word(30),word(24),word(26))
 }
 data class AfMode(val current:Int,val writable:Boolean,val values:Set<Int>)
 fun afModeDescriptor(data:ByteArray):AfMode?{
  if(data.size<12)return null
  fun u(i:Int)=(data[i].toInt()and 255)or((data[i+1].toInt()and 255)shl 8)
  if(u(0)!=0xd05d || u(2)!=4 || data[9].toInt()!=2)return null
  val count=u(10);if(count !in 1..32 || data.size<12+count*2)return null
  return AfMode(u(7),data[4].toInt()==1,(0 until count).map{u(12+it*2)}.toSet())
 }
 fun map(x:Float,y:Float,viewWidth:Float,viewHeight:Float,imageWidth:Int,imageHeight:Int):Pair<Float,Float>?{
  if(!x.isFinite() || !y.isFinite() || !viewWidth.isFinite() || !viewHeight.isFinite() || viewWidth<=0 || viewHeight<=0 || imageWidth<=0 || imageHeight<=0)return null
  val scale=minOf(viewWidth/imageWidth,viewHeight/imageHeight);val w=imageWidth*scale;val h=imageHeight*scale
  val left=(viewWidth-w)/2;val top=(viewHeight-h)/2
  if(x<left || x>=left+w || y<top || y>=top+h)return null
  return ((x-left)/w) to ((y-top)/h)
 }
 fun target(g:Geometry,x:Float,y:Float):Pair<Int,Int>?{
  if(!x.isFinite()||!y.isFinite()||x !in 0f..1f||y !in 0f..1f)return null
  return (x*(g.width-1)).toInt() to (y*(g.height-1)).toInt()
 }
}
