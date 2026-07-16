import ChatTTS
import torch
import soundfile as sf
import os

print("Initializing ChatTTS...")
chat = ChatTTS.Chat()
print("Loading model...")
chat.load(source="huggingface", compile=False)

print("Sampling speaker...")
torch.manual_seed(3000)
spk = chat.sample_random_speaker()
print("Speaker shape:", spk.shape if hasattr(spk, 'shape') else type(spk))

print("Inferring simple text...")
# We use refined text settings
params_refine = ChatTTS.Chat.RefineTextParams(
    prompt='[laugh]'
)
params_infer = ChatTTS.Chat.InferCodeParams(
    spk_emb=spk,
    temperature=0.3,
    top_P=0.7,
    top_K=20
)

wavs = chat.infer(["你好呀，张师兄，[laugh] 这是第一章的对话。[uv_break]"], 
                  params_refine_text=params_refine,
                  params_infer_code=params_infer)

print("Inference completed! Number of wavs:", len(wavs))
if len(wavs) > 0:
    wav_data = wavs[0]
    print("Wav data shape:", wav_data.shape, "dtype:", wav_data.dtype)
    # wav_data is normally a 2D array or 1D array.
    # Usually in ChatTTS, the output of infer is a list of numpy float arrays of shape (1, channels, samples) or similar.
    # Let's inspect it and save it as a test wav file.
    output_path = r"C:\Users\wjs\.gemini\antigravity\brain\daf9dfed-40a2-4c2a-96a7-848fa06f7f14\scratch\chattts_test.wav"
    
    # If 3D (1, channels, samples), flatten it or convert to 2D
    if len(wav_data.shape) == 3:
        wav_data = wav_data[0] # remove batch dim
    if len(wav_data.shape) == 2 and wav_data.shape[0] == 1:
        wav_data = wav_data[0] # convert mono 1D
        
    sf.write(output_path, wav_data, 24000) # ChatTTS native rate is 24000Hz
    print("Saved test audio to", output_path)
