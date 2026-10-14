package com.hamilton.stage.performance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import kotlin.math.*

private val Gold=Color(0xFFE9C583)
private val Cream=Color(0xFFFFF1D3)
private val Muted=Color(0xFFC7B798)
private val Theater=Color(0xE617110B)

@Composable
fun YearPanel(director:PerformanceDirector) {
    val ui=director.ui
    val context=LocalContext.current
    val density=LocalDensity.current
    val cellPx=with(density){186.dp.toPx()}
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(Theater).border(1.dp,Gold.copy(alpha=.42f),RoundedCornerShape(22.dp)).padding(vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(85.dp).clip(RoundedCornerShape(12.dp))
            .pointerInput(director,cellPx) {
                detectSpatialDragGesture(context,onDragStart={_,_->director.beginYearDrag()},onDragEnd={director.endYearDrag()},onDragCancel={director.cancelYearDrag()}) {director.dragYear(it.dragAmount.x/cellPx)}
            },contentAlignment=Alignment.Center) {
            // Fixed centre guide: the moving dates settle into this notch on release.
            Box(Modifier.align(Alignment.BottomCenter).width(52.dp).height(3.dp).background(Gold))
            val years=if(ui.years.isEmpty())listOf(1776,1780,1781,1789,1791,1800) else ui.years.map{it.year}
            years.forEachIndexed { index,year ->
                val distance=abs(index-ui.yearPosition)
                val alpha=(1f-distance*.29f).coerceIn(.18f,1f)
                Box(Modifier.offset{IntOffset(((index-ui.yearPosition)*cellPx).roundToInt(),0)}.width(172.dp).fillMaxHeight()
                    .graphicsLayer {this.alpha=alpha;scaleX=1f-min(distance,.9f)*.16f;scaleY=scaleX}
                    .clickable(enabled=director.ready){director.chooseYear(index)},contentAlignment=Alignment.Center) {
                    Text(year.toString(),color=if(distance<.48f)Gold else Muted,fontFamily=FontFamily.Serif,fontSize=54.sp,fontWeight=FontWeight.Medium)
                }
            }
        }
        val title=ui.years.getOrNull(ui.yearIndex)?.title?:"纽约 · 革命初遇"
        Text(title,color=Cream,fontSize=23.sp,textAlign=TextAlign.Center,modifier=Modifier.padding(top=8.dp))
    }
}

@Composable
fun BiographyPanel(director:PerformanceDirector) {
    val ui=director.ui
    val density=LocalDensity.current
    val actor=ui.selected ?: return
    key(actor.role) {
    Column(Modifier.fillMaxWidth().wrapContentHeight()
        .onSizeChanged { director.measureBiography(actor.role,with(density){it.height.toDp().value} * BIOGRAPHY_WIDTH_METRES / 480f) }
        .clip(RoundedCornerShape(22.dp)).background(Theater)
        .border(1.dp,Gold.copy(alpha=.35f),RoundedCornerShape(22.dp)).padding(horizontal=26.dp,vertical=16.dp)) {
        Text(actor.name,color=Gold,fontSize=30.sp,fontWeight=FontWeight.Medium)
        Text(actor.english,color=Muted,fontSize=20.sp,modifier=Modifier.padding(top=3.dp))
        Text(actor.sentences.joinToString(" "),color=Cream,fontSize=23.sp,lineHeight=30.sp,modifier=Modifier.padding(top=10.dp))
    }
    }
}

@Composable
fun HoverNameCard(director:PerformanceDirector) {
    val ui=director.ui
    val member=director.actors[ui.hoverCardRole]?.member ?: return
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
        Box(Modifier.height(104.dp).widthIn(min=180.dp,max=500.dp)
            .graphicsLayer { alpha=ui.hoverCardAlpha; translationY=(1f-ui.hoverCardAlpha)*8f }
            .clip(RoundedCornerShape(percent=50)).background(Color(0xF2262626))
            .clickable { director.select(member.role) }.padding(horizontal=36.dp),contentAlignment=Alignment.Center) {
            Text(member.name,color=Cream,fontSize=36.sp,fontWeight=FontWeight.Medium,maxLines=1,textAlign=TextAlign.Center)
        }
    }
}
