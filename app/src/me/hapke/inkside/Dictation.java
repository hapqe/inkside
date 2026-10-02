package me.hapke.inkside;

import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.EditText;

/**
 * Voice input: recording, transcription and inserting dictated text.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class Dictation {
    private final MainActivity act;

    /** Text box the running dictation belongs to (null = the chat). */
    private String dictationTextFieldId;

    private java.io.File voiceFile;

    private long voiceStartedAt = 0;
    /** Input hint from before dictation took the field over. */
    private String voiceSavedHint = null;

    private final Runnable voiceLevelPoll = new Runnable() {
        @Override
        public void run() {
            android.media.MediaRecorder r = act.voiceRecorder;
            if (r == null) return;
            float level = 0f;
            try {
                // getMaxAmplitude: peak since the last call, 0..32767. Log-ish curve so
                // normal speech fills the bars without shouting.
                int amp = r.getMaxAmplitude();
                level = (float) Math.min(1.0, Math.sqrt(amp / 12000.0));
            } catch (RuntimeException ignored) {
            }
            if (act.chatVoiceWave != null) act.chatVoiceWave.setLevel(level);
            if (act.miniChat != null) act.miniChat.setVoiceLevel(level);
            act.saveHandler.postDelayed(this, 70);
        }
    };

    Dictation(MainActivity act) {
        this.act = act;
    }

    void toggleVoiceInput() {
        if (act.voiceRecorder != null) {
            stopVoiceRecording(true);
            return;
        }
        if (!act.aiEnabled || !act.computers.requireHost("Dictation")) return;
        if (act.voiceTranscribing) return;
        if (act.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},
                    MainActivity.REQ_RECORD_AUDIO);
            return;
        }
        startVoiceRecording();
    }

    void startVoiceRecording() {
        try {
            voiceFile = new java.io.File(act.getCacheDir(), "dictation.m4a");
            android.media.MediaRecorder r = new android.media.MediaRecorder(act);
            r.setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION);
            r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
            // The model works at 16 kHz mono; recording at that rate keeps uploads small.
            r.setAudioSamplingRate(16000);
            r.setAudioChannels(1);
            r.setAudioEncodingBitRate(64000);
            r.setOutputFile(voiceFile.getAbsolutePath());
            r.prepare();
            playRecordStartTone();
            r.start();
            act.voiceRecorder = r;
            voiceStartedAt = System.currentTimeMillis();
            showVoiceListening(true);
            haptic(android.view.HapticFeedbackConstants.GESTURE_START);
        } catch (Exception e) {
            act.voiceRecorder = null;
            act.conversations.appendChat("warn", "could not start recording: " + e.getMessage());
        }
    }

    /** @param transcribe false drops the clip (discard button, or the app going to background). */
    void stopVoiceRecording(boolean transcribe) {
        if (!transcribe) {
            // A dropped clip never sends anything that was waiting on it.
            act.sendAfterDictation = false;
            act.miniSendAfterDictation = false;
            dictationTextFieldId = null;
        }
        android.media.MediaRecorder r = act.voiceRecorder;
        act.voiceRecorder = null;
        if (r == null) return;
        showVoiceListening(false);
        haptic(transcribe ? android.view.HapticFeedbackConstants.GESTURE_END
                : android.view.HapticFeedbackConstants.REJECT);
        boolean ok = true;
        try {
            r.stop();
        } catch (RuntimeException e) {
            ok = false; // stop right after start: no audio was captured
        } finally {
            r.release();
        }
        if (!ok || !transcribe || voiceFile == null || act.bridge == null) return;
        final byte[] audio = Documents.readCacheFile(voiceFile);
        if (audio == null) return;
        act.voiceTranscribing = true;
        if (act.miniChat != null) act.miniChat.setTranscribing(true);
        setTranscribingUi(true);
        // showVoiceListening(false) above already put the original hint back.
        final String hint = act.chatInput != null && act.chatInput.getHint() != null
                ? act.chatInput.getHint().toString() : "";
        if (act.chatInput != null) act.chatInput.setHint("Transcribing…");
        act.bridge.transcribe(audio, "audio/mp4", new BridgeClient.Callback<String>() {
            @Override
            public void onSuccess(String text) {
                if (act.isDead()) return;
                finishTranscribing(hint);
                haptic(android.view.HapticFeedbackConstants.CONFIRM);
                if (act.miniSendAfterDictation && act.miniChat == null && act.miniClosedDraft != null) {
                    // The instant chat closed while this was transcribing: send anyway.
                    String draft = act.miniClosedDraft.trim();
                    String spoken = text == null ? "" : text.trim();
                    act.miniClosedDraft = null;
                    act.miniSendAfterDictation = false;
                    act.conversations.sendTextViaChat(draft.isEmpty() ? spoken
                            : spoken.isEmpty() ? draft : draft + " " + spoken);
                    return;
                }
                insertDictation(text);
                if (act.miniSendAfterDictation && act.miniChat != null) {
                    act.miniSendAfterDictation = false;
                    act.sendAfterDictation = false;
                    act.instantChat.sendFromMiniChat(act.miniChat.text());
                } else if (act.sendAfterDictation) {
                    act.sendAfterDictation = false;
                    act.conversations.sendChat();
                }
            }

            @Override
            public void onError(String message) {
                if (act.isDead()) return;
                finishTranscribing(hint);
                haptic(android.view.HapticFeedbackConstants.REJECT);
                act.miniSendAfterDictation = false;
                act.sendAfterDictation = false;
                dictationTextFieldId = null;
                act.miniClosedDraft = null;
                act.conversations.appendChat("warn", "dictation: " + message);
            }
        });
    }

    /** A short buzz for what dictation just did; follows the system's touch feedback setting. */
    private void haptic(int kind) {
        try {
            act.getWindow().getDecorView().performHapticFeedback(kind);
        } catch (Exception ignored) {
            // No vibrator or feedback disabled: nothing to do.
        }
    }

    /** Mic buttons, wave and spinner take the current theme (called when the theme changes). */
    void restyleForTheme() {
        boolean live = act.voiceRecorder != null;
        if (act.chatVoiceWave != null) act.chatVoiceWave.setColor(act.M3_PRIMARY);
        if (act.chatMicSpinner != null) {
            act.chatMicSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(act.M3_PRIMARY));
        }
        styleInlineMic(live && dictationTextFieldId != null);
        styleChatMic(live);
        if (act.chatMicDiscardButton != null) act.applyIconSelected(act.chatMicDiscardButton, false);
    }

    private void styleInlineMic(boolean live) {
        if (act.inlineMicButton == null) return;
        if (live) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(act.M3_PRIMARY);
            act.inlineMicButton.setBackground(act.withHoverRipple(d, true));
            act.inlineMicButton.setColorFilter(new PorterDuffColorFilter(act.M3_PRIMARY_CONTAINER, PorterDuff.Mode.SRC_IN));
            act.inlineMicButton.setContentDescription("Stop dictation");
        } else {
            act.applyIconSelected(act.inlineMicButton, false);
            act.inlineMicButton.setContentDescription("Dictate into this text box");
        }
    }

    private void styleChatMic(boolean live) {
        if (act.chatMicButton == null) return;
        if (!live) {
            act.applyIconSelected(act.chatMicButton, false);
            act.chatMicButton.setContentDescription("Dictate");
            return;
        }
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        // Solid primary (not the Send button's primary container) marks the live mic.
        d.setColor(act.M3_PRIMARY);
        act.chatMicButton.setBackground(act.withHoverRipple(d, true));
        act.chatMicButton.setColorFilter(new PorterDuffColorFilter(act.M3_PRIMARY_CONTAINER, PorterDuff.Mode.SRC_IN));
        act.chatMicButton.setContentDescription("Stop dictation");
    }

    /**
     * Listening look, shared with the instant chat: solid primary mic (no pulse), a
     * plain "Listening…" hint, live voice bars, and a discard button tinted like the
     * attach clip.
     */
    private void showVoiceListening(boolean on) {
        if (act.miniChat != null) act.miniChat.setListening(on);
        act.saveHandler.removeCallbacks(voiceLevelPoll);
        if (act.chatVoiceWave != null) {
            act.chatVoiceWave.setColor(act.M3_PRIMARY);
            act.chatVoiceWave.setRunning(on);
        }
        if (on) voiceLevelPoll.run();
        styleInlineMic(on && dictationTextFieldId != null);
        if (act.chatMicButton == null) return;
        if (act.chatMicDiscardButton != null) {
            act.applyIconSelected(act.chatMicDiscardButton, false);
            act.chatMicDiscardButton.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        // No blinking caret while the mic is live: the field is not taking typing.
        if (act.chatInput != null) act.chatInput.setCursorVisible(!on && !act.voiceTranscribing);
        styleChatMic(on);
        if (!on) {
            // Dropped clips restore the hint here; transcribed ones after the answer.
            if (!act.voiceTranscribing && voiceSavedHint != null && act.chatInput != null) {
                act.chatInput.setHint(voiceSavedHint);
                voiceSavedHint = null;
            }
            return;
        }
        if (act.chatInput != null) {
            if (voiceSavedHint == null) {
                voiceSavedHint = act.chatInput.getHint() != null ? act.chatInput.getHint().toString() : "";
            }
            act.chatInput.setHint("Listening…");
        }
    }

    /** Short, low "we're listening" blip — a soft ~0.12s tone around 220 Hz. */
    private void playRecordStartTone() {
        new Thread(() -> {
            try {
                final int rate = 44100;
                final int n = rate * 120 / 1000;
                short[] pcm = new short[n];
                for (int i = 0; i < n; i++) {
                    double t = i / (double) rate;
                    // Slight downward glide reads as "start" without sounding like an alarm.
                    double f = 247.0 - 40.0 * (i / (double) n);
                    double env = Math.min(1.0, i / (rate * 0.008))
                            * Math.pow(1.0 - i / (double) n, 1.6);
                    double v = Math.sin(2 * Math.PI * f * t) + 0.25 * Math.sin(4 * Math.PI * f * t);
                    pcm[i] = (short) (v * env * 0.45 * Short.MAX_VALUE / 1.25);
                }
                android.media.AudioTrack track = new android.media.AudioTrack.Builder()
                        .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .setAudioFormat(new android.media.AudioFormat.Builder()
                                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate)
                                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(n * 2)
                        .build();
                track.write(pcm, 0, n);
                track.play();
                Thread.sleep(200);
                track.release();
            } catch (Throwable ignored) {
            }
        }, "cc-rec-tone").start();
    }

    /** Spinning indicator in place of the mic while the transcript is on its way. */
    private void setTranscribingUi(boolean on) {
        if (act.chatMicButton != null) {
            act.chatMicButton.setAlpha(1f);
            act.chatMicButton.setVisibility(on ? View.GONE : View.VISIBLE);
        }
        if (act.chatMicSpinner != null) {
            act.chatMicSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(act.M3_PRIMARY));
            act.chatMicSpinner.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        if (act.inlineMicSpinner != null) act.inlineMicSpinner.setVisibility(on ? View.VISIBLE : View.GONE);
        if (act.inlineMicButton != null) act.inlineMicButton.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    private void finishTranscribing(String hint) {
        act.voiceTranscribing = false;
        if (act.miniChat != null) act.miniChat.setTranscribing(false);
        setTranscribingUi(false);
        if (act.chatInput != null) act.chatInput.setCursorVisible(true);
        if (act.chatInput != null) act.chatInput.setHint(hint);
    }

    /**
     * Mic in the text style bar: dictate into the selected / edited text box. Opens the
     * box for editing first, so the words land at its cursor.
     */
    void toggleTextBoxDictation() {
        if (act.voiceRecorder != null) {
            stopVoiceRecording(true);
            return;
        }
        if (!act.aiEnabled || !act.computers.requireHost("Dictation")) return;
        if (act.voiceTranscribing || act.canvas == null) return;
        CanvasTextField tf = act.textTools.textFieldForStyleUi();
        if (tf == null) return;
        if (act.inlineEditor == null || !act.inlineEditor.isActive() || !tf.id.equals(act.inlineEditor.fieldId())) {
            act.textTools.openTextFieldEditor(tf.id);
        }
        dictationTextFieldId = tf.id;
        toggleVoiceInput();
        if (act.voiceRecorder == null) dictationTextFieldId = null;  // permission prompt / failure
    }

    /** Put the transcript at the cursor, with a space against any neighbouring text. */
    private void insertDictation(String text) {
        // Dictation started from a text box goes back into that text box.
        String fieldId = dictationTextFieldId;
        dictationTextFieldId = null;
        if (fieldId != null && act.canvas != null && text != null && !text.trim().isEmpty()) {
            if (act.inlineEditor != null && act.inlineEditor.isActive() && fieldId.equals(act.inlineEditor.fieldId())
                    && act.inlineEditor.input() != null) {
                insertAtCursor(act.inlineEditor.input(), text);
                return;
            }
            CanvasTextField tf = act.canvas.findTextField(fieldId);
            if (tf != null) {
                String cur = tf.text == null ? "" : tf.text;
                String sep = cur.isEmpty() || Character.isWhitespace(cur.charAt(cur.length() - 1)) ? "" : " ";
                act.canvas.setTextFieldTextLive(fieldId, cur + sep + text.trim());
                act.persistence.scheduleSave();
                return;
            }
        }
        insertDictationIntoChat(text);
    }

    private static void insertAtCursor(EditText target, String text) {
        android.text.Editable ed = target.getText();
        int start = Math.max(0, target.getSelectionStart());
        int end = Math.max(start, target.getSelectionEnd());
        String piece = text.trim();
        if (start > 0 && !Character.isWhitespace(ed.charAt(start - 1))) piece = " " + piece;
        if (end < ed.length() && !Character.isWhitespace(ed.charAt(end))) piece = piece + " ";
        ed.replace(start, end, piece);
        target.setSelection(start + piece.length());
    }

    private void insertDictationIntoChat(String text) {
        // While the instant chat is up, dictation belongs to it.
        EditText target = act.miniChat != null ? act.miniChat.input() : act.chatInput;
        if (target == null || text == null || text.trim().isEmpty()) return;
        android.text.Editable ed = target.getText();
        int start = Math.max(0, target.getSelectionStart());
        int end = Math.max(start, target.getSelectionEnd());
        String piece = text.trim();
        if (start > 0 && !Character.isWhitespace(ed.charAt(start - 1))) piece = " " + piece;
        if (end < ed.length() && !Character.isWhitespace(ed.charAt(end))) piece = piece + " ";
        ed.replace(start, end, piece);
        target.setSelection(start + piece.length());
    }
}
