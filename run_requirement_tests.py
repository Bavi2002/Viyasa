#!/usr/bin/env python3
"""
Automated Requirement-Based Verification Runner for Vyasa (FixLocal).
Executes iterative test cycles (Scan -> Fix -> Repeat) verifying:
1. REWRITE: Grammar, tenses, double negatives, phrasing
2. TRANSLATE: Multi-lingual (French, Spanish, German, Hindi, Tamil), verifying context, correct script, and zero repetition loops
3. SUMMARIZE: Bullet points and One-Liner summaries
4. CONTINUE: Conversational and narrative thought continuation
"""

import subprocess
import time
import re
import sys

def run_cmd(cmd):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return p.stdout.strip()

def send_test_task(task, text, param="", model="QWEN", backend="CPU", timeout=65):
    # Clear logcat first
    run_cmd("adb logcat -c")
    
    # Sanitize quotes for adb shell passing
    safe_text = text.replace("'", "")
    safe_param = param.replace("'", "")
    cmd = [
        "adb", "shell", "am", "broadcast",
        "-a", "com.fixlocal.android.TEST_TASK",
        "-n", "com.fixlocal.android/.ui.TestInferenceReceiver",
        "--es", "task", task,
        "--es", "input", f'"{safe_text}"',
        "--es", "param", f'"{safe_param}"',
        "--es", "model", model,
        "--es", "backend", backend
    ]
    subprocess.run(cmd, capture_output=True, text=True)
    
    # Poll logcat for result
    start_time = time.time()
    while time.time() - start_time < timeout:
        logs = run_cmd("adb logcat -d -s 'Vyasa-TestRunner:*'")
        if "TEST_RESULT:" in logs:
            match = re.search(r"TEST_RESULT:.*?input='(.*?)'\s*\|\s*output='(.*?)'\s*\|\s*latency=(\d+)ms", logs, re.DOTALL)
            if match:
                output_str = match.group(2).replace("\\n", "\n")
                return {
                    "input": match.group(1),
                    "output": output_str,
                    "latency_ms": int(match.group(3)),
                    "raw_line": match.group(0)
                }
        if "TEST_FAILED:" in logs or "TEST_ERROR:" in logs:
            return {"error": logs}
        time.sleep(1)
        
    return {"error": f"Timeout after {timeout}s"}

def check_repetition(text):
    # Check if any word repeated 3+ times consecutively
    if re.search(r"(\b\S+\b)(?:\s+\1){2,}", text, re.IGNORECASE):
        return True
    # Check if any character chunk repeated 3+ times
    if re.search(r"(.{2,15}?)\1{3,}", text):
        return True
    return False

def run_cycle(cycle_num):
    print(f"\n=======================================================")
    print(f"             CYCLE {cycle_num}: SCAN & VERIFY")
    print(f"=======================================================\n")
    
    test_cases = [
        # --- REWRITE ---
        {
            "id": "RW-01",
            "name": "Rewrite: Irregular Past Tense & Spacing",
            "task": "REWRITE",
            "input": "yesterday i go to shop and buyed milk",
            "param": "NATURAL",
            "model": "QWEN",
            "validate": lambda out: "went" in out.lower() and "bought" in out.lower() and not check_repetition(out)
        },
        {
            "id": "RW-02",
            "name": "Rewrite: Double Negative Resolution",
            "task": "REWRITE",
            "input": "he don't know nothing about this",
            "param": "NATURAL",
            "model": "QWEN",
            "validate": lambda out: ("doesn't know anything" in out.lower() or "does not know anything" in out.lower() or "knows nothing" in out.lower()) and not check_repetition(out)
        },
        {
            "id": "RW-03",
            "name": "Rewrite: Professional Phrasing",
            "task": "REWRITE",
            "input": "i am looking forward to hear from you soon",
            "param": "PROFESSIONAL",
            "model": "QWEN",
            "validate": lambda out: "hearing" in out.lower() and not check_repetition(out)
        },
        
        # --- TRANSLATE ---
        {
            "id": "TR-01",
            "name": "Translate: English -> Spanish Context",
            "task": "TRANSLATE",
            "input": "Good morning! How are you today?",
            "param": "Spanish",
            "model": "QWEN",
            "validate": lambda out: ("buenos" in out.lower() or "cómo estás" in out.lower() or "cómo está" in out.lower()) and not check_repetition(out)
        },
        {
            "id": "TR-02",
            "name": "Translate: English -> French Accuracy",
            "task": "TRANSLATE",
            "input": "Good morning! How are you today?",
            "param": "French",
            "model": "QWEN",
            "validate": lambda out: ("bonjour" in out.lower() or "comment" in out.lower()) and not check_repetition(out)
        },
        {
            "id": "TR-03",
            "name": "Translate: English -> German Meaning",
            "task": "TRANSLATE",
            "input": "Good morning! How are you today?",
            "param": "German",
            "model": "QWEN",
            "validate": lambda out: ("morgen" in out.lower() or "wie" in out.lower()) and not check_repetition(out)
        },
        {
            "id": "TR-04",
            "name": "Translate: English -> Hindi (Devanagari & No Loops)",
            "task": "TRANSLATE",
            "input": "Good morning! How are you today?",
            "param": "Hindi",
            "model": "QWEN",
            "validate": lambda out: any('\u0900' <= c <= '\u097F' for c in out) and not check_repetition(out)
        },
        {
            "id": "TR-05",
            "name": "Translate: English -> Tamil (No Word Repetition)",
            "task": "TRANSLATE",
            "input": "Good morning! How are you today?",
            "param": "Tamil",
            "model": "QWEN",
            "validate": lambda out: any('\u0B80' <= c <= '\u0BFF' for c in out) and not check_repetition(out)
        },
        
        # --- SUMMARIZE ---
        {
            "id": "SM-01",
            "name": "Summarize: Multi-paragraph to Bullets",
            "task": "SUMMARIZE",
            "input": "Artificial intelligence models are rapidly shrinking in size while preserving conversational and grammatical quality. On-device execution ensures that sensitive personal messages never leave the user's phone, giving complete privacy without subscription fees.",
            "param": "Bullets",
            "model": "QWEN",
            "validate": lambda out: ("•" in out or "-" in out or len(out) > 20) and not check_repetition(out)
        },
        {
            "id": "SM-02",
            "name": "Summarize: Executive One-Liner",
            "task": "SUMMARIZE",
            "input": "In today's engineering sync, the team decided to migrate from the old 135M model to Gemma 3 270M and Qwen 0.6B Q4. We verified that text selection in WhatsApp works seamlessly without app switching.",
            "param": "One-Liner",
            "model": "QWEN",
            "validate": lambda out: len(out.strip()) > 15 and not check_repetition(out)
        },
        
        # --- CONTINUE WRITING ---
        {
            "id": "CW-01",
            "name": "Continue Writing: Thought Extension",
            "task": "CONTINUE",
            "input": "Regarding our conversation yesterday about the project timeline,",
            "param": "",
            "model": "QWEN",
            "validate": lambda out: len(out.strip()) > len("Regarding our conversation yesterday about the project timeline,") and not check_repetition(out)
        }
    ]
    
    passed_count = 0
    results = []
    
    for case in test_cases:
        print(f"[{case['id']}] Running: {case['name']}...")
        res = send_test_task(
            task=case["task"],
            text=case["input"],
            param=case["param"],
            model=case["model"],
            backend="CPU"
        )
        
        if "error" in res:
            print(f"       -> FAILED: {res['error']}")
            results.append((case, False, res['error'], 0))
        else:
            output = res["output"]
            latency = res["latency_ms"]
            is_valid = case["validate"](output)
            rep = check_repetition(output)
            
            if is_valid and not rep:
                print(f"       -> PASSED ({latency}ms): '{output}'")
                passed_count += 1
                results.append((case, True, output, latency))
            else:
                print(f"       -> FAILED: Output failed requirement validation (repetition={rep}). Got: '{output}'")
                results.append((case, False, output, latency))
                
    pass_rate = (passed_count / len(test_cases)) * 100
    print(f"\nCycle {cycle_num} Summary: {passed_count}/{len(test_cases)} Passed ({pass_rate:.1f}%)")
    return results, pass_rate

def main():
    print("Initializing Vyasa 3-Cycle Requirement Verification...")
    
    cycles_data = []
    for cycle in range(1, 4):
        results, pass_rate = run_cycle(cycle)
        cycles_data.append((cycle, pass_rate, results))
        if cycle < 3:
            print(f"\nCompleted Cycle {cycle}. Pausing 2s before next cycle...\n")
            time.sleep(2)
            
    print("\n=======================================================")
    print("             FINAL 3-CYCLE SUMMARY REPORT              ")
    print("=======================================================")
    all_passed = True
    for c, pr, _ in cycles_data:
        print(f" Cycle {c}: Pass Rate = {pr:.1f}%")
        if pr < 100.0:
            all_passed = False
            
    if all_passed:
        print("\nALL REQUIREMENT USECASES PASSED ACROSS ALL 3 CYCLES!")
        sys.exit(0)
    else:
        print("\nSome tests failed during iterative cycles.")
        sys.exit(1)

if __name__ == "__main__":
    main()
