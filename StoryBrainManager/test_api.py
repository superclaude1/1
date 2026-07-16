import urllib.request
import urllib.parse
import json

url = "http://127.0.0.1:18083/api/generate"
data = {
    "text": "你好，我是碧瑶。这是用MOSS-TTS-Nano生成的语音。",
    "demo_id": "demo-6",
    "enable_text_normalization": "0",
    "enable_normalize_tts_text": "1",
    "seed": "1234"
}

encoded_data = urllib.parse.urlencode(data).encode('utf-8')
req = urllib.request.Request(url, data=encoded_data, method="POST")
# FastAPI Form fields expect application/x-www-form-urlencoded content type
req.add_header("Content-Type", "application/x-www-form-urlencoded")

print("Sending request to MOSS-TTS-Nano server...")
try:
    with urllib.request.urlopen(req) as response:
        status_code = response.getcode()
        print("Status code:", status_code)
        if status_code == 200:
            result = json.loads(response.read().decode('utf-8'))
            print("Success! Keys in response:", list(result.keys()))
            print("Audio base64 length:", len(result.get("audio_base64", "")))
            print("Sample rate:", result.get("sample_rate"))
            print("Run status:", result.get("run_status"))
        else:
            print("Error response:", response.read().decode('utf-8'))
except Exception as e:
    print("Request failed:", e)
