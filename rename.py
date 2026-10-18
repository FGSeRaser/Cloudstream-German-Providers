import os

old_pkg = "com.bnyro"
new_pkg = "com.seraser"

root_dir = "."
print("Starte automatisches Refactoring von com.bnyro zu com.seraser...")

# 1. Textinhalte in allen .kt-Dateien ersetzen
for dirpath, dirnames, filenames in os.walk(root_dir):
    if any(p in dirpath for p in ["build", ".git", ".gradle"]):
        continue
    for filename in filenames:
        if filename.endswith(".kt"):
            file_path = os.path.join(dirpath, filename)
            with open(file_path, "r", encoding="utf-8") as f:
                content = f.read()
            
            if old_pkg in content:
                new_content = content.replace(old_pkg, new_pkg)
                with open(file_path, "w", encoding="utf-8") as f:
                    f.write(new_content)
                print(f"Code aktualisiert in: {filename}")

# 2. Physische Ordner von 'bnyro' zu 'seraser' umbenennen
for dirpath, dirnames, filenames in os.walk(root_dir, topdown=False):
    if any(p in dirpath for p in ["build", ".git", ".gradle"]):
        continue
    if "bnyro" in dirnames:
        old_path = os.path.join(dirpath, "bnyro")
        new_path = os.path.join(dirpath, "seraser")
        
        if os.path.exists(new_path):
            # Falls seraser schon existiert, Inhalte rüberschieben
            for item in os.listdir(old_path):
                s = os.path.join(old_path, item)
                d = os.path.join(new_path, item)
                if not os.path.exists(d):
                  os.rename(s, d)
            os.rmdir(old_path)
        else:
            os.rename(old_path, new_path)
        print(f"Ordner umbenannt: {old_path} -> {new_path}")

print("Fertig! Alles auf com.seraser umgestellt.")