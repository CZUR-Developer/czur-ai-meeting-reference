package com.czur.reference.application.speaker;

import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.speaker.SpeakerIdentityProvider;
import com.czur.reference.application.port.speaker.SpeakerIdentityProvider.AudioWindow;
import com.czur.reference.application.port.speaker.SpeakerIdentityProvider.MeetingSpeakers;
import com.czur.reference.application.port.speaker.SpeakerIdentityProvider.SpeakerSample;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 每次会后调用只下载一次同场录音，按最终 speaker 时间窗提取 3～5 秒样本。
 *
 * <p>只处理已由公共 ArtifactStore 登记的内容；Ogg 等容器交 FFmpeg 解码，裸
 * PCM 按外置采样配置解码，统一输出讯飞要求的 16k／单声道／16bit PCM。
 * 多个相邻短句先合并，仍不足时连接同一个 speaker 的若干窗口，不把整场混音
 * 当作单人样本。临时文件归本次调用所有，正常／失败均关闭并删除。</p>
 */
final class FfmpegSpeakerSamples implements AutoCloseable {
    private final Path directory;
    private final Path source;
    private final Path sample;
    private final boolean rawPcm;
    private final ReferenceSpeakerIdentityConfig config;

    FfmpegSpeakerSamples(MeetingSpeakers meeting, ArtifactStore artifacts,
            ReferenceSpeakerIdentityConfig config) throws Exception {
        this.config = config;
        directory = Files.createTempDirectory("reference-speaker-");
        source = directory.resolve("source.audio");
        sample = directory.resolve("sample.pcm");
        rawPcm = "application/octet-stream".equals(meeting.audio().intent().spec().mediaType());
        try (var read = artifacts.openRead(meeting.audio().intent().binding())) {
            if (!meeting.audio().equals(read.artifact())) throw new IOException("speaker audio descriptor mismatch");
            Files.copy(read.content(), source);
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    byte[] sample(SpeakerSample speaker) throws Exception {
        List<AudioWindow> windows = selectWindows(speaker.windows());
        if (windows.stream().mapToLong(window -> window.endMs() - window.startMs()).sum() < 3000) {
            throw new SpeakerIdentityProvider.SampleUnavailable("speaker sample is too short");
        }
        List<String> command = new ArrayList<>(List.of(config.ffmpeg(), "-nostdin", "-hide_banner",
                "-loglevel", "error", "-y"));
        if (rawPcm) command.addAll(List.of("-f", "s16le", "-ar", Integer.toString(config.pcmSampleRate()),
                "-ac", Integer.toString(config.pcmChannels())));
        command.addAll(List.of("-i", source.toString(), "-filter_complex", filter(windows),
                "-map", "[voice]", "-vn", "-ac", "1", "-ar", "16000", "-f", "s16le",
                "-acodec", "pcm_s16le", sample.toString()));
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                throw new IOException("speaker sample conversion timed out");
            }
            if (process.exitValue() != 0) throw new IOException("speaker sample conversion failed");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        byte[] bytes = Files.readAllBytes(sample);
        if (bytes.length < 3 * 16000 * 2) {
            throw new SpeakerIdentityProvider.SampleUnavailable("speaker audio ended before selected window");
        }
        return bytes;
    }

    /** 正常短句以时间顺序合并／连接，最多选八段及五秒，不包含其他 speaker 的空档。 */
    static List<AudioWindow> selectWindows(List<AudioWindow> values) {
        List<AudioWindow> merged = new ArrayList<>();
        for (var window : values.stream().sorted(Comparator.comparingLong(AudioWindow::startMs)).toList()) {
            if (!merged.isEmpty() && window.startMs() <= merged.getLast().endMs() + 100) {
                var previous = merged.removeLast();
                merged.add(new AudioWindow(previous.startMs(), Math.max(previous.endMs(), window.endMs())));
            } else merged.add(window);
        }
        List<AudioWindow> selected = new ArrayList<>();
        long remaining = 5000;
        for (var window : merged) {
            if (remaining == 0 || selected.size() == 8) break;
            long duration = Math.min(remaining, window.endMs() - window.startMs());
            selected.add(new AudioWindow(window.startMs(), window.startMs() + duration));
            remaining -= duration;
        }
        return List.copyOf(selected);
    }

    private static String filter(List<AudioWindow> windows) {
        StringBuilder filter = new StringBuilder();
        for (int index = 0; index < windows.size(); index++) {
            var window = windows.get(index);
            filter.append(String.format(Locale.ROOT,
                    "[0:a]atrim=start=%.3f:end=%.3f,asetpts=PTS-STARTPTS[a%d];",
                    window.startMs() / 1000.0, window.endMs() / 1000.0, index));
        }
        for (int index = 0; index < windows.size(); index++) filter.append("[a").append(index).append(']');
        return filter.append("concat=n=").append(windows.size()).append(":v=0:a=1[voice]").toString();
    }

    @Override public void close() throws IOException {
        Files.deleteIfExists(sample);
        Files.deleteIfExists(source);
        Files.deleteIfExists(directory);
    }
}
