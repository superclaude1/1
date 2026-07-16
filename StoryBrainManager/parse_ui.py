import xml.etree.ElementTree as ET

path = r"C:\Users\wjs\.gemini\antigravity\brain\daf9dfed-40a2-4c2a-96a7-848fa06f7f14\window_dump.xml"
tree = ET.parse(path)
root = tree.getroot()

for node in root.findall(".//node"):
    res_id = node.get("resource-id")
    text = node.get("text")
    bounds = node.get("bounds")
    if res_id in ["com.storybrain.app:id/dialogueText", "com.storybrain.app:id/narrationText"]:
        print(f"[{res_id}] {text} -> {bounds}")
