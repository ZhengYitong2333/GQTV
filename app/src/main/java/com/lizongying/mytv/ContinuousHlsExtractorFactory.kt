/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lizongying.mytv

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.BundledHlsMediaChunkExtractor
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader
import java.io.EOFException

/** Retains pending AVC NAL data across HLS chunks when repairing a 1.2.1 extraction failure.
 * The normal extractor remains the default. This is used by the bounded per-route retry.
 */
@OptIn(UnstableApi::class)
internal class ContinuousHlsExtractorFactory : HlsExtractorFactory {
    private val fallback = DefaultHlsExtractorFactory()

    override fun createExtractor(uri: Uri, format: Format, muxedCaptionFormats: List<Format>?,
        timestampAdjuster: TimestampAdjuster, responseHeaders: Map<String, List<String>>,
        input: ExtractorInput, playerId: PlayerId): HlsMediaChunkExtractor {
        var flags = DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        val captions = muxedCaptionFormats ?: listOf(Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_CEA608).build())
        if (muxedCaptionFormats != null) flags = flags or DefaultTsPayloadReaderFactory.FLAG_OVERRIDE_CAPTION_DESCRIPTORS
        format.codecs?.takeIf { it.isNotEmpty() }?.let { codecs ->
            if (!MimeTypes.containsCodecsCorrespondingToMimeType(codecs, MimeTypes.AUDIO_AAC))
                flags = flags or DefaultTsPayloadReaderFactory.FLAG_IGNORE_AAC_STREAM
            if (!MimeTypes.containsCodecsCorrespondingToMimeType(codecs, MimeTypes.VIDEO_H264))
                flags = flags or DefaultTsPayloadReaderFactory.FLAG_IGNORE_H264_STREAM
        }
        val base = DefaultTsPayloadReaderFactory(flags, captions)
        val readers = object : TsPayloadReader.Factory {
            override fun createInitialPayloadReaders() = base.createInitialPayloadReaders()
            override fun createPayloadReader(streamType: Int, info: TsPayloadReader.EsInfo): TsPayloadReader? {
                val reader = base.createPayloadReader(streamType, info) ?: return null
                // The 1.2.1 EOF loop only flushes direct PesReaders. Preserve AVC's pending NAL
                // until an actual boundary arrives in the continuous next HLS chunk.
                return if (streamType == 0x1B) object : TsPayloadReader by reader {} else reader
            }
        }
        val extractor = TsExtractor(TsExtractor.MODE_HLS, timestampAdjuster, readers)
        val transportStream = try {
            input.resetPeekPosition()
            extractor.sniff(input)
        } catch (_: EOFException) {
            false
        } finally {
            input.resetPeekPosition()
        }
        return if (transportStream) BundledHlsMediaChunkExtractor(extractor, format, timestampAdjuster)
            else fallback.createExtractor(uri, format, muxedCaptionFormats, timestampAdjuster, responseHeaders, input, playerId)
    }
}
