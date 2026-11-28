# 음성 → 글자 (sherpa-onnx SenseVoice: 일본어·중국어·영어·한국어·광둥어). 기술 음성을 장면에 맞게 배정하고 자막을 달 때 씀
# 준비: pip install sherpa-onnx · 모델은 GitHub 릴리스 k2-fsa/sherpa-onnx asr-models 의 sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2 (저장소에 넣지 않음)
# 사용: python3 tools/asr.py <모델 폴더> <wav/ogg...>
import sys, subprocess, numpy as np, sherpa_onnx
md = sys.argv[1]
rec = sherpa_onnx.OfflineRecognizer.from_sense_voice(model=f'{md}/model.int8.onnx', tokens=f'{md}/tokens.txt', use_itn=True, language='auto', num_threads=4)
for f in sys.argv[2:]:
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', f, '-ac', '1', '-ar', '16000', '-f', 'f32le', '-'], capture_output=True).stdout
    a = np.frombuffer(raw, np.float32)
    st = rec.create_stream(); st.accept_waveform(16000, a); rec.decode_stream(st)
    print(f'{len(a)/16000:5.2f}s\t{f.split("/")[-1]}\t{st.result.text}', flush=True)
