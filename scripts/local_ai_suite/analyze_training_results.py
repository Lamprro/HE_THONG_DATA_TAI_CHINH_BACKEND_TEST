import json

summary = json.loads(open('output/local-ai-review/rank-100-v3-clean-trained/summary.json', encoding='utf-8').read())

def fmt(val, pattern="{:.4f}"):
    return "None" if val is None else pattern.format(val)

for v in ["V1", "V2", "V3"]:
    data = summary[v]
    print(f"\n==================== VARIANT {v} ====================")
    print("Selected:", data.get("selected"))
    print("Baseline:", data.get("baseline"))
    print("Challenger:", data.get("challenger"))
    print("Gate checks:", data.get("gate"))
    
    b = data.get("baseline")
    c = data.get("challenger")
    
    dev = data.get("development", {})
    retro = data.get("retrospective", {})
    
    print("\n--- DEVELOPMENT (2017 - 2024-06) ---")
    for m in dev:
        met = dev[m]
        auc = fmt(met.get("mean_auc"))
        ic = fmt(met.get("rank_ic"), "{:+.4f}")
        t = fmt(met.get("rank_ic_t"), "{:+.2f}")
        win = fmt(met.get("win_fraction") * 100 if met.get("win_fraction") is not None else None, "{:.1f}%")
        flag = " [SELECTED]" if m == data.get("selected") else " [BASELINE]" if m == b else " [CHALLENGER]" if m == c else ""
        print(f"  {m:18s}{flag:14s} | AUC: {auc} | Rank IC: {ic} (t={t}) | Win%: {win}")
        
    print("\n--- RETROSPECTIVE (2025 - 2026-09) ---")
    for m in retro:
        met = retro[m]
        auc = fmt(met.get("mean_auc"))
        ic = fmt(met.get("rank_ic"), "{:+.4f}")
        t = fmt(met.get("rank_ic_t"), "{:+.2f}")
        win = fmt(met.get("win_fraction") * 100 if met.get("win_fraction") is not None else None, "{:.1f}%")
        flag = " [SELECTED]" if m == data.get("selected") else " [BASELINE]" if m == b else " [CHALLENGER]" if m == c else ""
        print(f"  {m:18s}{flag:14s} | AUC: {auc} | Rank IC: {ic} (t={t}) | Win%: {win}")

    if "paired_development" in data:
        print("\n--- PAIRED COMPARISON (DEV: Challenger vs Baseline) ---")
        p = data["paired_development"]
        print(f"  AUC Gain: {fmt(p.get('mean_origin_auc_gain'))} | Win Fraction: {fmt(p.get('win_fraction')*100, '{:.1f}%')} | Paired t: {fmt(p.get('paired_auc_t'), '{:+.2f}')}")
    if "paired_retrospective" in data:
        print("\n--- PAIRED COMPARISON (RETRO: Challenger vs Baseline) ---")
        p = data["paired_retrospective"]
        print(f"  AUC Gain: {fmt(p.get('mean_origin_auc_gain'))} | Win Fraction: {fmt(p.get('win_fraction')*100, '{:.1f}%')} | Paired t: {fmt(p.get('paired_auc_t'), '{:+.2f}')}")
