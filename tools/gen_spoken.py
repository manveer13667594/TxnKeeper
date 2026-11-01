#!/usr/bin/env python3
"""Synthetic SPOKEN transaction corpus (what Whisper might hand to SpokenTransactionParser).
Output: spoken_cases.tsv   id \t category \t text \t expected
expected = txns separated by ';;'  each = title~amount~type~payment~daysAgo~description  (empty = don't care/null)
Reference 'now' used by the test harness: 2026-10-29 12:00 (a Thursday).
"""
import random, datetime, sys
R = random.Random(int(sys.argv[1]) if len(sys.argv) > 1 else 7)
NOW = datetime.date(2026, 10, 29)

ONES = "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split()
TENS = {20:"twenty",30:"thirty",40:"forty",50:"fifty",60:"sixty",70:"seventy",80:"eighty",90:"ninety"}
def words(n):
    if n < 20: return ONES[n]
    if n < 100: return TENS[n//10*10] + ("" if n%10==0 else " "+ONES[n%10])
    if n < 1000:
        r = words(n//100) + " hundred"
        return r + ("" if n%100==0 else R.choice([" and "," "]) + words(n%100))
    if n < 100000:
        r = words(n//1000) + " thousand"
        return r + ("" if n%1000==0 else " " + words(n%1000))
    return None

def amount_phrase(n, style=None):
    """returns (spoken text of the amount incl. currency maybe, value)"""
    style = style or R.choice(["digits","digits","words","comma","rs","rupee_sym","k","words_rs","rupees_after"])
    if style == "k" and n % 1000 == 0 and n >= 1000: return f"{n//1000}k"
    if style == "k": style = "digits"
    if style == "words" or style == "words_rs":
        w = words(n)
        if w:
            return w + (R.choice([" rupees"," rupees"," rs"]) if style=="words_rs" else "")
        style = "digits"
    if style == "comma" and n >= 1000: return f"{n:,}"
    if style == "comma": style = "digits"
    if style == "rs": return R.choice(["Rs ","Rs. ","rs "]) + str(n)
    if style == "rupee_sym": return "₹" + (f"{n:,}" if n>=1000 and R.random()<.5 else str(n))
    if style == "rupees_after": return f"{n} rupees"
    return str(n)

def rand_amount():
    t = R.random()
    if t < .30: return R.choice([20,30,40,45,60,75,80,120,150,180,250,300,350,450,500,600,750,800,950])
    if t < .70: return R.choice([1000,1200,1250,1500,1800,2000,2300,2500,3000,3500,4200,5000,6500,7500,8000,9999])
    if t < .92: return R.choice([10000,12000,15000,18500,25000,30000,45000,50000,75000,85000])
    return R.choice([100000,150000,200000,250000,500000])

PEOPLE = ["Rahul","Amit","Priya","Suresh","Mom","Dad","Gurpreet","Kuldip Kaur","Neha","Ramesh","Anil","Sunita","Vikram","Manpreet","Deepak"]
SHOPS = ["Dominos","Zomato","Swiggy","Big Bazaar","Reliance Fresh","Airtel","Uber","Ola","Apollo Pharmacy","Electricity Board","Amazon","Flipkart","Starbucks","Decathlon","Jio","Blinkit","McDonalds"]
PURPOSES = ["groceries","rent","lunch","dinner","petrol","recharge","medicine","electricity bill","school fees","movie tickets","taxi fare","gym membership","birthday gift","books","breakfast","coffee","internet bill","water bill"]
ITEMS = ["vegetables","fruits","shoes","milk","headphones","a shirt","groceries","bread and eggs","medicines","a phone cover","flowers","chocolates"]
SOURCES = ["salary","bonus","freelance work","pocket money","dividend","interest","cashback","refund"]
PAYS = [("UPI",["using UPI","by UPI","on GPay","via PhonePe","through Paytm","using Google Pay","on PhonePe"]),
        ("CASH",["in cash","by cash","using cash","cash"]),
        ("CARD",["by card","using my credit card","with debit card","on credit card"]),
        ("BANK TRANSFER",["through NEFT","via IMPS","by bank transfer","using net banking"])]
def pay_phrase():
    m, opts = R.choice(PAYS); return m, R.choice(opts)

WD = ["Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"]
MONTHS = ["January","February","March","April","May","June","July","August","September","October","November","December"]
def ordinal(d):
    if 10 <= d % 100 <= 20: s = "th"
    else: s = {1:"st",2:"nd",3:"rd"}.get(d%10,"th")
    return f"{d}{s}"
def date_phrase():
    """(phrase, daysAgo or None)"""
    k = R.choice(["yesterday","yesterday","dby","ago","ago","weekday_last","weekday_on","absolute","absolute2","lastnight","today"])
    if k == "yesterday": return "yesterday", 1
    if k == "dby": return "day before yesterday", 2
    if k == "lastnight": return "last night", 1
    if k == "today": return R.choice(["today","this morning"]), None
    if k == "ago":
        n = R.choice([2,3,4,5,6,10]); w = words(n) if R.random()<.5 else str(n)
        return f"{w} days ago", n
    if k.startswith("weekday"):
        wd = R.randrange(7); today = NOW.weekday()
        back = (today - wd) % 7
        if k == "weekday_last": return f"last {WD[wd]}", back or 7
        if back == 0: return f"on {WD[(wd+1)%7]}", (today-(wd+1)%7)%7
        return f"on {WD[wd]}", back
    n = R.randrange(2, 45); d = NOW - datetime.timedelta(days=n)
    if d.month == NOW.month and d.day == NOW.day: n += 1; d = NOW - datetime.timedelta(days=n)
    if k == "absolute": return f"on {ordinal(d.day)} {MONTHS[d.month-1]}", n
    return f"on {MONTHS[d.month-1]} {ordinal(d.day)}", n

class Txn:
    def __init__(s, **kw): s.__dict__.update(dict(title="",amount=0,type="",pay="",days=None,desc="",dpos=""), **kw)
    def label(s):
        return "~".join([s.title, str(s.amount), s.type, s.pay, "" if s.days is None else str(s.days), s.desc])

def cap(x): return x[:1].upper()+x[1:]

def expense_clause(first=True, with_amt_style=None, force_party=None):
    """returns (text, Txn, has_verb)"""
    n = rand_amount(); a = amount_phrase(n, with_amt_style)
    k = R.choice(["paid_party_purpose","paid_party","spent_on","spent_on_at","bought","gave","sent","paid_purpose","party_first","transfer","paid_name_amt"])
    party = force_party or R.choice(PEOPLE if R.random()<.55 else SHOPS)
    purpose = R.choice(PURPOSES); item = R.choice(ITEMS)
    verb = R.choice(["paid","paid","spent","gave","sent","transferred"])
    if k == "paid_party_purpose":
        return f"{R.choice(['paid','paid','I paid'])} {a} to {party} for {purpose}", Txn(title=party,amount=n,type="EXPENSE",desc=cap(purpose)), 1
    if k == "paid_party":
        return f"paid {a} to {party}", Txn(title=party,amount=n,type="EXPENSE"), 1
    if k == "paid_name_amt":
        return f"paid {party} {a} for {purpose}", Txn(title=party,amount=n,type="EXPENSE",desc=cap(purpose)), 1
    if k == "spent_on":
        return f"spent {a} on {purpose}", Txn(title=cap(purpose),amount=n,type="EXPENSE"), 1
    if k == "spent_on_at":
        shop = R.choice(SHOPS)
        return f"spent {a} on {purpose} at {shop}", Txn(title=shop,amount=n,type="EXPENSE",desc=cap(purpose)), 1
    if k == "bought":
        t = item.replace("a ","",1) if item.startswith("a ") else item
        return f"bought {item} for {a}", Txn(title=cap(t),amount=n,type="EXPENSE"), 1
    if k == "gave":
        return f"gave {a} to {party}", Txn(title=party,amount=n,type="EXPENSE"), 1
    if k == "sent":
        return f"sent {a} to {party} for {purpose}", Txn(title=party,amount=n,type="EXPENSE",desc=cap(purpose)), 1
    if k == "paid_purpose":
        return f"paid {a} for {purpose}", Txn(title=cap(purpose),amount=n,type="EXPENSE"), 1
    if k == "party_first":
        shop = R.choice(SHOPS)
        return f"{shop} {a}", Txn(title=shop,amount=n), 0
    if k == "transfer":
        return f"{verb} {a} to {party}", Txn(title=party,amount=n,type="EXPENSE"), 1

def income_clause():
    n = rand_amount(); a = amount_phrase(n)
    party = R.choice(PEOPLE if R.random()<.6 else ["Amazon","Flipkart","Infosys","HDFC Bank"]); src = R.choice(SOURCES)
    k = R.choice(["received_from","got_from","sent_me","paid_me","salary","refund","received_for","credited"])
    if k == "received_from":
        return f"received {a} from {party}", Txn(title=party,amount=n,type="INCOME"), 1
    if k == "got_from":
        return f"got {a} from {party}", Txn(title=party,amount=n,type="INCOME"), 1
    if k == "received_for":
        p = R.choice(PURPOSES)
        return f"received {a} from {party} for {p}", Txn(title=party,amount=n,type="INCOME",desc=cap(p)), 1
    if k == "sent_me":
        pn = R.choice([p for p in PEOPLE if " " not in p and p not in ("Mom","Dad")])
        return f"{pn} sent me {a}", Txn(title=pn,amount=n,type="INCOME"), 1
    if k == "paid_me":
        pn = R.choice([p for p in PEOPLE if " " not in p and p not in ("Mom","Dad")])
        return f"{pn} paid me {a}", Txn(title=pn,amount=n,type="INCOME"), 1
    if k == "salary":
        return R.choice([f"salary credited {a}", f"got {a} salary", f"received salary of {a}"]), Txn(title="Salary",amount=n,type="INCOME"), 1
    if k == "refund":
        shop = R.choice(["Amazon","Flipkart","Myntra","Swiggy"])
        return f"got a refund of {a} from {shop}", Txn(title=shop,amount=n,type="INCOME"), 1
    if k == "credited":
        s = R.choice(["bonus","interest","cashback","dividend"])
        return f"{s} credited {a}", Txn(title=cap(s),amount=n,type="INCOME"), 1

def with_pay(text, t):
    if R.random() < .35:
        m, ph = pay_phrase(); t.pay = m
        if text.startswith(("paid","spent","gave","sent","transferred","I paid")) or "from" in text or "to" in text:
            text = f"{text} {ph}"
        else:
            text = f"{text} {ph}"
    return text, t

def with_date(text, t):
    if R.random() < .4:
        ph, d = date_phrase(); t.days = d
        if R.random()<.35: text, t.dpos = f"{ph} {text}", "lead"
        else: text, t.dpos = f"{text} {ph}", "trail"
    return text, t

FILL_PRE = ["", "", "", "okay so ", "so ", "uh ", "um, ", "hey, ", "please add ", "note down that I ", "add a transaction, ", "I want to add that I ", "so basically ", "alright, "]
def decorate(s):
    s = R.choice(FILL_PRE) + s
    if R.random() < .25:
        w = s.split(" ")
        if len(w) > 3:
            i = R.randrange(2, len(w)); w.insert(i, R.choice(["uh,","um,","uhh,","hmm,"]))
        s = " ".join(w)
    if R.random() < .15: s += R.choice([" thank you", " thanks", " that's it", " yeah"])
    if R.random() < .12: s = s + " [BLANK_AUDIO]"
    return s

def punctuate(s):
    if R.random() < .55: s = cap(s)
    if R.random() < .6: s += "."
    if R.random() < .12: s = s.lower().replace(".","")
    return s

cases = []
def add(cat, text, txns):
    cases.append((cat, text, txns))

# ---- single transactions
for _ in range(220):
    t = R.random()
    text, tx, _v = (expense_clause() if t < .62 else income_clause())
    text, tx = with_pay(text, tx); text, tx = with_date(text, tx)
    add("single", punctuate(text), [tx])
# ---- noisy single (fillers, whisper artifacts)
for _ in range(120):
    text, tx, _v = expense_clause() if R.random()<.6 else income_clause()
    text, tx = with_pay(text, tx); text, tx = with_date(text, tx)
    add("noisy", punctuate(decorate(text)), [tx])
# ---- short verb-less notes
NOTES = [("petrol","Petrol"),("chai","Chai"),("lunch","Lunch"),("auto fare","Auto Fare"),("parking","Parking"),("groceries","Groceries"),("movie","Movie"),("coffee","Coffee"),("newspaper","Newspaper")]
for _ in range(50):
    n = rand_amount() % 2000 or 100; w, ti = R.choice(NOTES); a = amount_phrase(n, R.choice(["digits","words_rs","rupees_after"]))
    form = R.choice(["{w} {a}","{a} {w}","{w} {a}"])
    text = form.format(w=w, a=a)
    tx = Txn(title=ti, amount=n)  # type not labelled for verb-less notes
    if R.random()<.4: m, ph = pay_phrase(); tx.pay = m; text += " " + ph
    add("short_note", punctuate(text), [tx])
# ---- multi transaction recordings
CONN = [", and then ", " and then ", ". Then ", ". Also ", ", also ", " and ", ", ", ". ", " after that ", ", plus ", ". Next "]
for _ in range(220):
    k = R.choice([2,2,2,3,3,4]); txns = []; parts = []
    shared_date = None; shared_pay = None
    mode = R.random()
    for i in range(k):
        if R.random() < .62: text, tx, _v = expense_clause()
        else: text, tx, _v = income_clause()
        parts.append((text, tx))
    tail = ""; head = ""
    if mode < .22:       # one date for everything
        ph, d = date_phrase()
        if R.random() < .5: head = ph + " "
        else: tail = " " + ph
        for _, tx in parts: tx.days = d
    elif mode < .40:     # one payment method for everything
        m, ph = pay_phrase(); tail = " " + R.choice(["all "+ph, "both "+ph] if k==2 else ["all "+ph]) if False else " " + ph
        # a trailing phrase is ambiguous; only claim 'all' when worded so
        tail = " " + ("both " if k==2 else "all ") + ph
        for _, tx in parts: tx.pay = m
    else:                # each its own date/pay sometimes
        for i,(text, tx) in enumerate(parts):
            if R.random() < .3: parts[i] = with_pay(text, tx)
            text, tx = parts[i]
            if R.random() < .25: parts[i] = with_date(text, tx)
    if mode >= .40:
        dated = [i for i,(_,tx) in enumerate(parts) if tx.days is not None]
        if len(dated) == 1:
            i = dated[0]; tx = parts[i][1]
            if (i == 0 and tx.dpos == "lead") or (i == k-1 and tx.dpos == "trail"):
                for _, o in parts: o.days = tx.days
    s = parts[0][0]
    for text, _tx in parts[1:]:
        s += R.choice(CONN) + text
    s = head + s + tail
    # sometimes drop the verb/subject of later same-kind clauses: handled by 'elided' category below
    add("multi", punctuate(decorate(s) if R.random()<.35 else s), [tx for _, tx in parts])
# ---- elided multi: "paid 500 to Rahul and 300 to Amit"
for _ in range(80):
    kind = R.choice(["E","I"]); k = R.choice([2,2,3]); vals = []
    if kind == "E":
        names = R.sample(PEOPLE+SHOPS, k); verb = R.choice(["paid","gave","sent","transferred"])
        amts = [rand_amount() for _ in range(k)]
        segs = [f"{amount_phrase(a)} to {n}" for a,n in zip(amts,names)]
        text = f"{verb} " + R.choice([", "," and "]).join(segs[:-1]) + R.choice([" and ",", and "]) + segs[-1]
        tx = [Txn(title=n,amount=a,type="EXPENSE") for a,n in zip(amts,names)]
    else:
        names = R.sample(PEOPLE, k); verb = R.choice(["received","got"])
        amts = [rand_amount() for _ in range(k)]
        segs = [f"{amount_phrase(a)} from {n}" for a,n in zip(amts,names)]
        text = f"{verb} " + ", ".join(segs[:-1]) + R.choice([" and ",", and "]) + segs[-1]
        tx = [Txn(title=n,amount=a,type="INCOME") for a,n in zip(amts,names)]
    add("multi_elided", punctuate(text), tx)
# ---- mixed list style: "lunch 200, dinner 300, auto 80"
for _ in range(40):
    k = R.choice([2,3,4]); picks = R.sample(NOTES, k); txns=[]; segs=[]
    for w, ti in picks:
        n = (rand_amount() % 900) or 50; segs.append(f"{w} {amount_phrase(n, R.choice(['digits','words','rs']))}")
        txns.append(Txn(title=ti, amount=n))
    sep = R.choice([", ", " and ", ", then "])
    text = "spent on " if False else ""
    add("list_style", punctuate(sep.join(segs)), txns)
# ---- colloquial amounts
for _ in range(40):
    n = R.choice([150,250,350,450,550,1500,2500,"hundred",100,50])
    if n == "hundred": a, v = "hundred rupees", 100
    elif isinstance(n,int) and n < 600 and n%100==50: a, v = f"{words(n//100)} fifty", n
    elif n == 1500 or n == 2500: a, v = f"{words(n//1000 if n>=2000 else 1)} thousand five hundred" if n==2500 else "fifteen hundred", n
    else: a, v = (str(n), n)
    party = R.choice(PEOPLE)
    add("colloquial", punctuate(f"paid {a} to {party}"), [Txn(title=party, amount=v, type="EXPENSE")])
# ---- negatives: no transaction at all
NEG = ["Hello, how are you?","What is the weather today","Remind me to call Rahul tomorrow","I walked 5000 steps today","Thank you.","[BLANK_AUDIO]","Um, uh, hmm.","Open the camera","I need to buy milk","The meeting is at 5 pm","I have two kids and one dog","Set an alarm for 6 am","I am going to the market","You","Call Mom"]
for t in NEG:
    for _ in range(3): add("negative", R.choice([t, t.lower(), t+"."]), [])

R.shuffle(cases)
with open("spoken_cases.tsv","w") as f:
    for i,(cat,text,txns) in enumerate(cases):
        f.write(f"{i}\t{cat}\t{text}\t{';;'.join(t.label() for t in txns)}\n")
from collections import Counter
print(len(cases), Counter(c[0] for c in cases))
