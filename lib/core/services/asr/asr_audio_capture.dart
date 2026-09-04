import 'dart:async';
import 'dart:math' as math;
import 'dart:typed_data';

import 'package:record/record.dart';

/// Minimal PCM microphone boundary used by non-system ASR providers.
abstract interface class AsrAudioCapture {
  Future<bool> hasPermission();

  Future<Stream<Uint8List>> start({required int sampleRate});

  Future<void> stop();

  Future<void> cancel();

  Future<void> dispose();
}

final class RecordAsrAudioCapture implements AsrAudioCapture {
  RecordAsrAudioCapture({AudioRecorder? recorder})
    : _recorder = recorder ?? AudioRecorder();

  final AudioRecorder _recorder;
  bool _started = false;
  bool _disposed = false;

  @override
  Future<bool> hasPermission() {
    _ensureNotDisposed();
    return _recorder.hasPermission();
  }

  @override
  Future<Stream<Uint8List>> start({required int sampleRate}) async {
    _ensureNotDisposed();
    if (_started) throw StateError('PCM audio capture is already active.');
    if (sampleRate <= 0) {
      throw ArgumentError.value(sampleRate, 'sampleRate', 'Must be positive');
    }
    final stream = await _recorder.startStream(
      RecordConfig(
        encoder: AudioEncoder.pcm16bits,
        sampleRate: sampleRate,
        numChannels: 1,
        // Keep the signal raw. Voice processing (especially AGC) flattens the
        // level meter on desktop and can distort the PCM expected by offline
        // acoustic models.
        autoGain: false,
        echoCancel: false,
        noiseSuppress: false,
        streamBufferSize: 4096,
      ),
    );
    _started = true;
    return stream;
  }

  @override
  Future<void> stop() async {
    if (_disposed || !_started) return;
    _started = false;
    await _recorder.stop();
  }

  @override
  Future<void> cancel() async {
    if (_disposed || !_started) return;
    _started = false;
    await _recorder.cancel();
  }

  @override
  Future<void> dispose() async {
    if (_disposed) return;
    if (_started) await cancel();
    _disposed = true;
    await _recorder.dispose();
  }

  void _ensureNotDisposed() {
    if (_disposed) throw StateError('PCM audio capture has been disposed.');
  }
}

/// Converts little-endian mono PCM16 into a stable 0–1 waveform value.
double normalizedPcm16Level(Uint8List bytes) {
  final sampleCount = bytes.length ~/ 2;
  if (sampleCount == 0) return 0;
  final data = ByteData.sublistView(bytes);
  var sumSquares = 0.0;
  for (var index = 0; index < sampleCount; index++) {
    final normalized = data.getInt16(index * 2, Endian.little) / 32768.0;
    sumSquares += normalized * normalized;
  }
  final rms = math.sqrt(sumSquares / sampleCount);
  // Lift normal speech while keeping room noise visually quiet.
  return math.pow((rms * 3.2).clamp(0.0, 1.0), 0.72).toDouble();
}
