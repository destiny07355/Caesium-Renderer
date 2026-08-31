import os
import sys
import json
import urllib.request
import subprocess
import shutil
import time
import re
import zipfile
import matplotlib.pyplot as plt

MC_VERSIONS_PRIORITY = ["1.21.11", "1.21.4", "1.21.3", "1.21.2", "1.21.1", "1.21"]
LOADER = "fabric"
MODS_DIR = "run/mods"
MODS_OPT_DIR = "run/mods_optimized"
MODS_STD_DIR = "run/mods_standalone"

MODS = {
    "immediatelyfast": "immediatelyfast",
    "entityculling": "entityculling",
    "lithium": "lithium",
    "ferrite-core": "ferrite-core",
    "modernfix": "modernfix",
    "badoptimizations": "badoptimizations",
    "c2me-fabric": "c2me-fabric",
    "krypton": "krypton",
    "dynamic-fps": "dynamic-fps",
    "spark": "spark"
}

def patch_mod_jar(jar_path):
    temp_jar = jar_path + ".tmp"
    try:
        with zipfile.ZipFile(jar_path, 'r') as zin, zipfile.ZipFile(temp_jar, 'w') as zout:
            for item in zin.infolist():
                content = zin.read(item.filename)
                if item.filename == 'fabric.mod.json':
                    try:
                        data = json.loads(content.decode('utf-8'))
                        if 'depends' in data and 'minecraft' in data['depends']:
                            data['depends']['minecraft'] = '*'
                        content = json.dumps(data, indent=2).encode('utf-8')
                    except Exception as e:
                        print(f"Failed to patch fabric.mod.json in {jar_path}: {e}")
                zout.writestr(item, content)
        shutil.move(temp_jar, jar_path)
        print(f"Patched {os.path.basename(jar_path)} for 1.21.11 compatibility.")
    except Exception as e:
        print(f"Error patching {jar_path}: {e}")
        if os.path.exists(temp_jar):
            os.remove(temp_jar)

def get_latest_version(slug):
    url = f"https://api.modrinth.com/v2/project/{slug}/version"
    req = urllib.request.Request(url, headers={'User-Agent': 'AntigravityBench/1.0'})
    try:
        with urllib.request.urlopen(req) as response:
            data = json.loads(response.read().decode())
            for ver_target in MC_VERSIONS_PRIORITY:
                for ver in data:
                    if ver_target in ver['game_versions'] and LOADER in ver['loaders']:
                        return ver['files'][0]['url'], ver['files'][0]['filename']
    except Exception as e:
        print(f"Failed to fetch {slug}: {e}")
    return None, None

def download_mods():
    os.makedirs(MODS_OPT_DIR, exist_ok=True)
    os.makedirs(MODS_STD_DIR, exist_ok=True)
    
    print("Downloading mods...")
    for name, slug in MODS.items():
        print(f"Fetching {name}...")
        url, filename = get_latest_version(slug)
        if url:
            opt_path = os.path.join(MODS_OPT_DIR, filename)
            if not os.path.exists(opt_path):
                urllib.request.urlretrieve(url, opt_path)
                patch_mod_jar(opt_path)
            
            if name == "spark":
                std_path = os.path.join(MODS_STD_DIR, filename)
                if not os.path.exists(std_path):
                    shutil.copy(opt_path, std_path)
            print(f"Downloaded {filename}")
        else:
            print(f"Could not find valid version for {name}")

def parse_output(output):
    load_time, min_fps, max_fps, moving_fps = [], [], [], []
    spark_link = None
    for line in output.splitlines():
        if "World Reload Time:" in line:
            m = re.search(r"World Reload Time: (\d+)ms", line)
            if m: load_time.append(int(m.group(1)))
        elif "Min Chunks (2) FPS:" in line:
            m = re.search(r"Min Chunks \(2\) FPS: ([\d.]+)", line)
            if m: min_fps.append(float(m.group(1)))
        elif "Max Chunks (32) FPS:" in line:
            m = re.search(r"Max Chunks \(32\) FPS: ([\d.]+)", line)
            if m: max_fps.append(float(m.group(1)))
        elif "Moving FPS:" in line:
            m = re.search(r"Moving FPS: ([\d.]+)", line)
            if m: moving_fps.append(float(m.group(1)))
        elif "spark.lucko.me" in line:
            m = re.search(r"https://spark\.lucko\.me/[a-zA-Z0-9]+", line)
            if m: spark_link = m.group(0)
            
    return load_time, min_fps, max_fps, moving_fps, spark_link

def run_benchmark():
    print("Running benchmark...")
    lock_file = os.path.join("run", "saves", "New World", "session.lock")
    if os.path.exists(lock_file):
        try:
            os.remove(lock_file)
        except Exception:
            pass
            
    cmd = ["powershell.cmd", "-c", ".\\gradlew runClient -Pbenchmark --no-daemon"]
    if os.name == 'nt':
        cmd = ["cmd", "/c", ".\\gradlew.bat runClient -Pbenchmark --no-daemon"]
    
    process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    output = []
    while True:
        line = process.stdout.readline()
        if not line:
            break
        print(line, end="")
        output.append(line)
    
    process.wait()
    return parse_output("".join(output))

def generate_visual_graph(results):
    modes = ['Standalone', 'Optimized']
    min_fps = [results['standalone']['min_fps'], results['optimized']['min_fps']]
    max_fps = [results['standalone']['max_fps'], results['optimized']['max_fps']]
    mov_fps = [results['standalone']['moving_fps'], results['optimized']['moving_fps']]
    
    x = range(len(modes))
    width = 0.25

    fig, ax = plt.subplots(figsize=(10, 6))
    
    bars1 = ax.bar([p - width for p in x], min_fps, width, label='Min Chunks FPS (2)', color='#e74c3c')
    bars2 = ax.bar(x, max_fps, width, label='Max Chunks FPS (32)', color='#3498db')
    bars3 = ax.bar([p + width for p in x], mov_fps, width, label='Moving Avg FPS', color='#2ecc71')

    ax.set_ylabel('Frames Per Second (FPS)', fontsize=12)
    ax.set_title('DestinyRenderer Benchmark Performance Comparison', fontsize=14, fontweight='bold')
    ax.set_xticks(x)
    ax.set_xticklabels(modes, fontsize=12, fontweight='bold')
    ax.legend()
    ax.grid(axis='y', linestyle='--', alpha=0.7)

    for bars in [bars1, bars2, bars3]:
        for bar in bars:
            height = bar.get_height()
            ax.annotate(f'{height:.1f}',
                        xy=(bar.get_x() + bar.get_width() / 2, height),
                        xytext=(0, 3),
                        textcoords="offset points",
                        ha='center', va='bottom', fontweight='bold')

    plt.tight_layout()
    graph_path = "benchmark_graph.png"
    plt.savefig(graph_path, dpi=300)
    plt.close()
    print(f"Graph generated and saved to {graph_path}")

def generate_txt_report(results, spark_links):
    std = results['standalone']
    opt = results['optimized']
    
    report = []
    report.append("==========================================================================")
    report.append("                   DESTINYRENDERER BENCHMARK REPORT                      ")
    report.append("==========================================================================")
    report.append("")
    report.append("1. PERFORMANCE METRICS COMPARISON")
    report.append("--------------------------------------------------------------------------")
    report.append(f"{'Metric':<25} | {'Standalone':<15} | {'Optimized (+Mods)':<15}")
    report.append("--------------------------------------------------------------------------")
    report.append(f"{'World Reload Time':<25} | {std['load_time_ms']:<15.0f} ms | {opt['load_time_ms']:<15.0f} ms")
    report.append(f"{'Min Chunks (2) FPS':<25} | {std['min_fps']:<15.1f} | {opt['min_fps']:<15.1f}")
    report.append(f"{'Max Chunks (32) FPS':<25} | {std['max_fps']:<15.1f} | {opt['max_fps']:<15.1f}")
    report.append(f"{'Moving Avg FPS':<25} | {std['moving_fps']:<15.1f} | {opt['moving_fps']:<15.1f}")
    report.append("--------------------------------------------------------------------------")
    report.append("")
    report.append("2. ASCII PERFORMANCE GRAPH")
    report.append("--------------------------------------------------------------------------")
    
    def make_ascii_bar(val, max_val=400, length=30):
        filled = int((val / max_val) * length) if max_val > 0 else 0
        return "█" * filled + "░" * (length - filled) + f" ({val:.1f} FPS)"

    report.append("Standalone Mode:")
    report.append(f"  Min Chunks : {make_ascii_bar(std['min_fps'])}")
    report.append(f"  Max Chunks : {make_ascii_bar(std['max_fps'])}")
    report.append(f"  Moving Avg : {make_ascii_bar(std['moving_fps'])}")
    report.append("")
    report.append("Optimized Mode (+Mods):")
    report.append(f"  Min Chunks : {make_ascii_bar(opt['min_fps'])}")
    report.append(f"  Max Chunks : {make_ascii_bar(opt['max_fps'])}")
    report.append(f"  Moving Avg : {make_ascii_bar(opt['moving_fps'])}")
    report.append("")
    report.append("3. SPARK PROFILER LINKS & DIAGNOSTICS")
    report.append("--------------------------------------------------------------------------")
    report.append(f"Standalone Spark Profile : {spark_links.get('standalone', 'https://spark.lucko.me/ (run /spark profiler in game)')}")
    report.append(f"Optimized Spark Profile  : {spark_links.get('optimized', 'https://spark.lucko.me/ (run /spark profiler in game)')}")
    report.append("==========================================================================")

    report_content = "\n".join(report)
    with open("benchmark_report.txt", "w", encoding="utf-8") as f:
        f.write(report_content)
    print("Report written to benchmark_report.txt")

def run_suite():
    # Force re-download & patch of existing jars if needed
    if os.path.exists(MODS_OPT_DIR):
        for f in os.listdir(MODS_OPT_DIR):
            if f.endswith('.jar'):
                patch_mod_jar(os.path.join(MODS_OPT_DIR, f))
    else:
        download_mods()
    
    results = {"standalone": {}, "optimized": {}}
    spark_links = {}
    
    for mode in ["standalone", "optimized"]:
        print(f"\n--- Starting {mode.upper()} Benchmarks ---")
        if os.path.exists(MODS_DIR):
            shutil.rmtree(MODS_DIR, ignore_errors=True)
        if os.path.exists("run/.fabric"):
            shutil.rmtree("run/.fabric", ignore_errors=True)
        
        src_dir = MODS_STD_DIR if mode == "standalone" else MODS_OPT_DIR
        shutil.copytree(src_dir, MODS_DIR)
        
        all_load, all_min, all_max, all_moving = [], [], [], []
        
        for i in range(1):
            print(f"Run {i+1}/1 for {mode}...")
            load, minf, maxf, movf, spark = run_benchmark()
            if load: all_load.extend(load)
            if minf: all_min.extend(minf)
            if maxf: all_max.extend(maxf)
            if movf: all_moving.extend(movf)
            if spark: spark_links[mode] = spark
            time.sleep(1)
            
        def avg(l): return sum(l)/len(l) if l else 0
        
        results[mode] = {
            "load_time_ms": avg(all_load),
            "min_fps": avg(all_min),
            "max_fps": avg(all_max),
            "moving_fps": avg(all_moving)
        }
        
    print("\n--- BENCHMARK RESULTS ---")
    print(json.dumps(results, indent=2))
    
    with open("benchmark_results.json", "w") as f:
        json.dump(results, f, indent=2)
        
    generate_txt_report(results, spark_links)
    generate_visual_graph(results)

if __name__ == "__main__":
    run_suite()
