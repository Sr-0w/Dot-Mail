package app.dot.mail.data

import app.dot.mail.data.model.HighlightRange
import java.text.Normalizer
import java.text.BreakIterator
import java.util.Locale

/** Same normalization for indexed documents, FTS terms, ranking and original-text highlight offsets. */
internal object SearchText {
    private val marks=Regex("\\p{M}+")
    fun normalize(text:String):String = Normalizer.normalize(text,Normalizer.Form.NFKD).replace(marks,"").lowercase(Locale.ROOT).replace("ß","ss")
    fun prefix(text:String,limit:Int):String {
        var end=minOf(text.length,limit.coerceAtLeast(0))
        if(end<text.length && end>0) {
            val boundaries=BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
            if(!boundaries.isBoundary(end)) end=boundaries.preceding(end).coerceAtLeast(0)
        }
        return text.substring(0,end)
    }
    private val tokens=Regex("[\\p{L}\\p{N}]+")
    fun words(text:String):List<String> = tokens.findAll(normalize(text)).map { it.value }.toList()
    fun match(value:String,prefix:Boolean=true):String? = words(value).takeIf { it.isNotEmpty() }?.joinToString(" ")?.let { "\"$it${if(prefix) "*" else ""}\"" }
    fun highlights(text:String,values:List<String>):List<HighlightRange> {
        val normalized=StringBuilder(); val starts=ArrayList<Int>(); val ends=ArrayList<Int>()
        var i=0
        while(i<text.length) {
            val start=i; val cp=text.codePointAt(i); i+=Character.charCount(cp)
            // A combining sequence is indivisible even though its marks disappear from the index.
            while(i<text.length && Character.getType(text.codePointAt(i)) in setOf(Character.NON_SPACING_MARK.toInt(),Character.COMBINING_SPACING_MARK.toInt(),Character.ENCLOSING_MARK.toInt())) i+=Character.charCount(text.codePointAt(i))
            val value=normalize(text.substring(start,i)); normalized.append(value)
            repeat(value.length) { starts+=start; ends+=i }
        }
        val ranges=ArrayList<HighlightRange>()
        val haystack=tokens.findAll(normalized).toList()
        for(value in values) {
            val needle=words(value); if(needle.isEmpty()) continue
            for(at in haystack.indices) {
                if(ranges.size>=100) break
                if(at+needle.size>haystack.size) break
                if(needle.indices.all { offset -> if(offset==needle.lastIndex) haystack[at+offset].value.startsWith(needle[offset]) else haystack[at+offset].value==needle[offset] }) {
                    val first=haystack[at].range.first
                    val last=haystack[at+needle.lastIndex].range.first+needle.last().length-1
                    ranges+=HighlightRange(starts[first],ends[last])
                }
            }
        }
        val merged=ArrayList<HighlightRange>()
        for(range in ranges.sortedBy { it.start }) {
            val last=merged.lastOrNull()
            if(last!=null && range.start<=last.endExclusive) merged[merged.lastIndex]=last.copy(endExclusive=maxOf(last.endExclusive,range.endExclusive)) else merged+=range
        }
        return merged
    }
    fun excerpt(text:String,values:List<String>,max:Int=220):Pair<String,List<HighlightRange>> {
        val clean=text.replace(Regex("\\s+")," ").trim()
        val first=highlights(clean,values).firstOrNull()?.start ?: 0
        var start=(first-60).coerceAtLeast(0)
        val boundaries=BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(clean) }
        if(start>0 && !boundaries.isBoundary(start)) start=boundaries.preceding(start).coerceAtLeast(0)
        var end=(start+max).coerceAtMost(clean.length)
        if(end<clean.length && end>0 && !boundaries.isBoundary(end)) end=boundaries.preceding(end).coerceAtLeast(start)
        val result=(if(start>0) "…" else "")+clean.substring(start,end)+(if(end<clean.length) "…" else "")
        return result to highlights(result,values)
    }
}
