package com.neoalive.tacz_sewv.audio;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/** Dev tool: runs a 16-bit PCM wav through {@link RadioVoiceDsp} for A/B against radioize_intercom.py. */
public final class RadioDspRender {

    public static void main(String[] args) throws IOException, UnsupportedAudioFileException {
        if (args.length < 2 || args[0].isEmpty() || args[1].isEmpty()) {
            System.err.println("usage: radioDspRender -Pin=voice.wav -Pout=radio.wav");
            return;
        }
        AudioInputStream src = AudioSystem.getAudioInputStream(new File(args[0]));
        AudioFormat f = src.getFormat();
        AudioFormat pcm = new AudioFormat(f.getSampleRate(), 16, f.getChannels(), true, false);
        AudioInputStream in = AudioSystem.getAudioInputStream(pcm, src);
        byte[] raw = in.readAllBytes();
        int ch = pcm.getChannels();
        int n = raw.length / 2 / ch;
        short[] mono = new short[n];
        for (int i = 0; i < n; i++) {
            int sum = 0;
            for (int c = 0; c < ch; c++) {
                int o = (i * ch + c) * 2;
                sum += (short) ((raw[o] & 0xFF) | (raw[o + 1] << 8));
            }
            mono[i] = (short) (sum / ch);
        }
        RadioVoiceDsp dsp = new RadioVoiceDsp(1, pcm.getSampleRate());
        short[] out = new short[n];
        for (int i = 0; i < n; i += 960) {
            int len = Math.min(960, n - i);
            short[] chunk = new short[len];
            System.arraycopy(mono, i, chunk, 0, len);
            System.arraycopy(dsp.process(chunk), 0, out, i, len);
        }
        byte[] bytes = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            bytes[i * 2] = (byte) out[i];
            bytes[i * 2 + 1] = (byte) (out[i] >> 8);
        }
        AudioFormat mf = new AudioFormat(pcm.getSampleRate(), 16, 1, true, false);
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(bytes), mf, n),
                AudioFileFormat.Type.WAVE, new File(args[1]));
        System.out.println("wrote " + args[1] + " (" + n + " samples at " + (int) pcm.getSampleRate() + " Hz)");
    }
}
