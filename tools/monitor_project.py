import json
import os
import re
import sys
import urllib.request
from datetime import datetime

# Safe stdout for Windows console
if sys.stdout and hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

STATE_FILE = os.path.join(os.path.dirname(__file__), "monitor_state.json")

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
}

def load_state():
    if os.path.exists(STATE_FILE):
        try:
            with open(STATE_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {"last_4pda_posts": 1, "last_github_issues": 0, "last_stars": 0}

def save_state(state):
    try:
        with open(STATE_FILE, "w", encoding="utf-8") as f:
            json.dump(state, f, indent=2, ensure_ascii=False)
    except Exception as e:
        print(f"[Warning] Failed to save state: {e}")

def check_4pda(topic_id="1127752"):
    url = f"https://4pda.to/forum/index.php?showtopic={topic_id}"
    req = urllib.request.Request(url, headers=HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=12) as resp:
            html = resp.read().decode("windows-1251", errors="replace")
    except Exception as e:
        return {"status": "error", "error": str(e)}

    # Count total posts by looking for post header links or anchors
    post_ids = re.findall(r'entry(\d+)', html)
    # Alternatively find messages
    posts_blocks = re.findall(r'<div[^>]*class=[\'"]postcolor[\'"][^>]*>(.*?)</div>', html, re.DOTALL)
    
    total_posts = max(len(post_ids), len(posts_blocks), 1)

    # Check for authors
    authors = re.findall(r'showuser=\d+[\'"][^>]*>([^<]+)</a>', html)
    latest_author = authors[-1].strip() if authors else "Unknown"

    return {
        "status": "ok",
        "url": url,
        "total_posts": total_posts,
        "latest_author": latest_author,
    }

def check_github(repo="NickDevo-o/tiktok-skip-ad"):
    api_url = f"https://api.github.com/repos/{repo}"
    req = urllib.request.Request(api_url, headers=HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        stars = data.get("stargazers_count", 0)
        forks = data.get("forks_count", 0)
        open_issues = data.get("open_issues_count", 0)

        # Fetch recent issues if any
        issues_list = []
        if open_issues > 0:
            issues_req = urllib.request.Request(f"{api_url}/issues?state=open", headers=HEADERS)
            with urllib.request.urlopen(issues_req, timeout=10) as i_resp:
                issues_data = json.loads(i_resp.read().decode("utf-8"))
                for item in issues_data:
                    issues_list.append({
                        "title": item.get("title"),
                        "user": item.get("user", {}).get("login"),
                        "url": item.get("html_url"),
                    })

        return {
            "status": "ok",
            "stars": stars,
            "forks": forks,
            "open_issues": open_issues,
            "issues": issues_list,
        }
    except Exception as e:
        return {"status": "error", "error": str(e)}

def check_habr(user="NickDev0-0"):
    # Check author profile articles to see if moved from sandbox to public
    url = f"https://habr.com/ru/users/{user}/articles/"
    req = urllib.request.Request(url, headers=HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            html = resp.read().decode("utf-8", errors="replace")
        has_public_article = "TikTok Ad Skipper" in html
        return {
            "status": "ok",
            "is_public": has_public_article,
            "note": "Статья уже на главной/в хабах!" if has_public_article else "На премодерации в Песочнице",
        }
    except urllib.error.HTTPError as e:
        return {"status": "restricted", "code": e.code, "note": "На премодерации в Песочнице"}
    except Exception as e:
        return {"status": "error", "error": str(e), "note": "Проверка не удалась"}

def run_monitor():
    print("=" * 60)
    print(f"📊 МОНИТОРИНГ ПРОЕКТА TIKTOK AD SKIPPER — {datetime.now().strftime('%d.%m.%Y %H:%M:%S')}")
    print("=" * 60)

    state = load_state()
    updates_found = False

    # 1. 4PDA
    print("\n[1] Форум 4PDA (тема #1127752):")
    pda_res = check_4pda()
    if pda_res["status"] == "ok":
        total = pda_res["total_posts"]
        print(f"    • Сообщений в теме: {total}")
        print(f"    • Последний автор: {pda_res['latest_author']}")
        if total > state.get("last_4pda_posts", 1):
            print(f"    🔥 ВНИМАНИЕ: Появились новые сообщения от пользователей (+{total - state.get('last_4pda_posts', 1)})!")
            updates_found = True
        else:
            print("    • Новых ответов пока нет (только шапка темы).")
        state["last_4pda_posts"] = total
    else:
        print(f"    [Ошибка проверки 4PDA]: {pda_res.get('error')}")

    # 2. GitHub
    print("\n[2] Репозиторий GitHub (NickDevo-o/tiktok-skip-ad):")
    gh_res = check_github()
    if gh_res["status"] == "ok":
        print(f"    • Звёзды (Stars): {gh_res['stars']}")
        print(f"    • Форки (Forks): {gh_res['forks']}")
        print(f"    • Открытые Issues: {gh_res['open_issues']}")
        if gh_res["open_issues"] > state.get("last_github_issues", 0):
            print(f"    🔥 ВНИМАНИЕ: Открыты новые баг-репорты/Issues на GitHub (+{gh_res['open_issues'] - state.get('last_github_issues', 0)})!")
            for iss in gh_res["issues"]:
                print(f"       - #{iss['title']} от @{iss['user']}: {iss['url']}")
            updates_found = True
        state["last_github_issues"] = gh_res["open_issues"]
        state["last_stars"] = gh_res["stars"]
    else:
        print(f"    [Ошибка проверки GitHub]: {gh_res.get('error')}")

    # 3. Habr
    print("\n[3] Хабр (Песочница / Статьи):")
    habr_res = check_habr()
    print(f"    • Статус: {habr_res['note']}")
    if habr_res.get("is_public"):
        print("    🎉 СТАТЬЯ ВЫШЛА ИЗ ПЕСОЧНИЦЫ В ОБЩИЙ ПОТОК ХАБРА!")
        updates_found = True

    save_state(state)
    print("\n" + "=" * 60)
    if updates_found:
        print("🔔 ЕСТЬ ОБНОВЛЕНИЯ, ТРЕБУЮЩИЕ ВНИМАНИЯ!")
    else:
        print("✅ Все платформы в норме, новых критических событий нет.")
    print("=" * 60)

if __name__ == "__main__":
    run_monitor()
