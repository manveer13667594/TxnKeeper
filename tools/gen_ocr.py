#!/usr/bin/env python3
"""Synthetic OCR corpus: payment-app screenshots, bank SMS, shop bills.
Output ocr_cases.tsv:  id \t category \t lines \t expected
lines  = height@@text joined by '||'
expected = title~amount~type~pay~YYYY-MM-DD~ref  (empty = don't care for title/type/ref, must-be-null for pay/date)
"""
import random, datetime, sys
R = random.Random(int(sys.argv[1]) if len(sys.argv) > 1 else 5)
NOW = datetime.date(2026, 10, 29)
ONES="zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split()
TENS={2:"twenty",3:"thirty",4:"forty",5:"fifty",6:"sixty",7:"seventy",8:"eighty",9:"ninety"}
def w(n):
    if n<20: return ONES[n]
    if n<100: return TENS[n//10]+("" if n%10==0 else " "+ONES[n%10])
    if n<1000: return ONES[n//100]+" hundred"+("" if n%100==0 else " "+w(n%100))
    return w(n//1000)+" thousand"+("" if n%1000==0 else " "+w(n%1000))
NAMES=["RAHUL SHARMA","Amit Kumar","PRIYA SINGH","Kuldip Kaur","Gurpreet Singh","Neha Verma","SURESH TRADERS","Dominos Pizza","Indian Oil","Apollo Pharmacy","Reliance Retail","Zomato","Swiggy","Hostinger Pte Ltd","Airtel Payments","Big Bazaar","Vikram Singh Gill","MANPREET KAUR","Sharma General Store","BSES Rajdhani"]
def amt():
    return R.choice([20,45,99,120,250,300,400,499,650,945,1051,1250,1800,2300,2622,4500,7500,12000,15000,45000])
def fmt_amt(n, dec=False):
    s = f"{n:,}"
    return s + (".00" if dec else "")
def rnd_date():
    d = NOW - datetime.timedelta(days=R.randrange(1,120)); return d
def time_s(): return f"{R.randrange(1,13):02d}:{R.randrange(60):02d} {R.choice(['am','pm','AM','PM'])}"
MON=["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"]
def ref12(): return "".join(R.choice("0123456789") for _ in range(12))
def utr(): return "".join(R.choice("0123456789") for _ in range(R.choice([10,12])))
def vpa(name): return name.split()[0].lower()+R.choice(["@okhdfcbank","@ybl","@paytm","@oksbi","@upi"])
cases=[]
def add(cat, lines, exp): cases.append((cat, lines, exp))
def exp(title="",amount=0,typ="",pay="",date=None,ref=""):
    return "~".join([title,str(amount),typ,pay,date.isoformat() if date else "",ref])

for _ in range(70):   # Google Pay
    n=amt(); name=R.choice(NAMES); d=rnd_date(); paid=R.random()<.7; r=ref12()
    ds=R.choice([f"{d.day} {MON[d.month-1]} {d.year}, {time_s()}", f"{d.day:02d}/{d.month:02d}/{d.year}", f"{MON[d.month-1]} {d.day}, {d.year}"])
    sym=R.choice(["₹","₹",""]) 
    L=[(40,("To " if paid else "From ")+name),(100,f"{sym}{fmt_amt(n)}"),(30,"Completed"),(24,ds),(24,"UPI transaction ID"),(26,r)]
    if R.random()<.5: L.insert(3,(26,f"Payment of {sym}{fmt_amt(n)} completed")) if False else None
    if R.random()<.4: L.append((24,R.choice(["Google Pay","Paid via Google Pay"])))
    add("gpay",L,exp(name.title() if name.isupper() else name,n,"EXPENSE" if paid else "INCOME","UPI",d,"Ref: "+r if False else ""))
for _ in range(60):   # PhonePe
    n=amt(); name=R.choice(NAMES); d=rnd_date(); paid=R.random()<.75; t="T"+str(R.randrange(10**18,10**19-1))[:19]
    L=[(30,"Payment Successful" if paid else "Payment Received"),(90,f"₹{fmt_amt(n)}"),(36,("Paid to " if paid else "Received from ")+name),
       (24,f"{MON[d.month-1]} {d.day}, {d.year} {time_s()}"),(24,f"Transaction ID {t}"),(24,"PhonePe")]
    add("phonepe",L,exp(name.title() if name.isupper() else name,n,"EXPENSE" if paid else "INCOME","UPI",d))
for _ in range(50):   # Paytm
    n=amt(); name=R.choice(NAMES); d=rnd_date(); paid=R.random()<.75; r=ref12()
    if n>=100000: n=45000
    L=[(30,"Paytm"),(30,R.choice(["Paid Successfully","Order Successful"]) if paid else "Money Received"),(90,f"₹{fmt_amt(n)}"),(24,f"Rupees {w(n).title()} Only"),
       (30,("To: " if paid else "From: ")+name),(24,f"{d.day} {MON[d.month-1]} {d.year}, {time_s()}"),(24,f"UPI Ref No: {r}")]
    add("paytm",L,exp(name.title() if name.isupper() else name,n,"EXPENSE" if paid else "INCOME","UPI",d,"Ref: "+r))
for _ in range(60):   # bank SMS (single text block split in lines)
    n=amt(); name=R.choice(NAMES); d=rnd_date(); kind=R.choice(["upi_dr","neft_cr","card","atm","imps_dr","upi_cr"]); r=ref12(); ac=R.randrange(1000,9999)
    bal=f"{R.randrange(500,99999)}.{R.randrange(10,99)}"
    if kind=="upi_dr":
        L=[f"Rs.{fmt_amt(n,True)} debited from A/c XX{ac} on {d.day:02d}-{d.month:02d}-{str(d.year)[2:]} to VPA {vpa(name)} (UPI Ref No {r}). Avl Bal Rs {bal}"]; e=exp("",n,"EXPENSE","UPI",d,"Ref: "+r)
    elif kind=="neft_cr":
        L=[f"Your a/c XXXX{ac} is credited with Rs {fmt_amt(n,True)} on {d.day:02d}/{d.month:02d}/{d.year} by NEFT from {name}. Avl bal Rs {bal}"]; e=exp("",n,"INCOME","BANK TRANSFER",d)
    elif kind=="card":
        L=[f"INR {fmt_amt(n,True)} spent on HDFC Bank Credit Card XX{ac} at {name.upper()} on {d.day:02d}-{MON[d.month-1]}-{str(d.year)[2:]}. Avl limit INR {R.randrange(10000,90000)}.00"]; e=exp("",n,"EXPENSE","CARD",d)
    elif kind=="atm":
        L=[f"Rs {fmt_amt(n,True)} withdrawn from A/c XX{ac} at ATM on {d.day:02d}/{d.month:02d}/{d.year}. Avl Bal Rs {bal}"]; e=exp("",n,"EXPENSE","",d)
    elif kind=="imps_dr":
        L=[f"A/c XX{ac} debited Rs.{fmt_amt(n,True)} on {d.day:02d}-{d.month:02d}-{d.year} IMPS to {name}. Ref {r}. Balance Rs {bal}"]; e=exp("",n,"EXPENSE","BANK TRANSFER",d,"Ref: "+r)
    else:
        L=[f"Rs {fmt_amt(n,True)} credited to A/c XX{ac} on {d.day:02d}-{d.month:02d}-{str(d.year)[2:]} from {vpa(name)} UPI Ref {r}. Bal Rs {bal}"]; e=exp("",n,"INCOME","UPI",d,"Ref: "+r)
    if R.random()<.6:
        import textwrap; L=textwrap.wrap(L[0],R.choice([32,38,44]))
    add("sms",[(26,x) for x in L],e)
for _ in range(50):   # shop bills
    d=rnd_date(); shop=R.choice(["SHARMA GENERAL STORE","BIG BAZAAR","RELIANCE FRESH","APOLLO PHARMACY","SUPER MART"])
    items=[(R.choice(["Rice 5kg","Atta","Milk","Sugar","Dal","Oil 1L","Soap","Biscuits","Tea"]),R.choice([40,55,120,240,310,89,150])) for _ in range(R.randrange(2,5))]
    tot=sum(p for _,p in items); pay=R.choice(["CASH","UPI","CARD",""])
    L=[(44,shop),(24,f"Bill No: {R.randrange(1000,9999)}"),(24,f"Date: {d.day:02d}/{d.month:02d}/{d.year}")]
    for it,p in items: L.append((24,f"{it}  {p}.00"))
    L.append((30,f"Total {tot}.00")); L.append((34,R.choice([f"Net Amount {tot}.00",f"Grand Total {tot}.00"])))
    if pay: L.append((24,{"CASH":"Paid by Cash","UPI":"Paid via UPI","CARD":"Card Payment Visa"}[pay]))
    add("bill",L,exp("",tot,"EXPENSE",pay,d))
for _ in range(25):   # noise: misread rupee symbol / dropped currency on GPay-like
    n=amt(); name=R.choice(NAMES); d=rnd_date(); r=ref12()
    L=[(40,"To "+name),(100,R.choice(["%"+fmt_amt(n),"€"+fmt_amt(n),"₹ "+fmt_amt(n),fmt_amt(n)])),(30,"Completed"),(24,f"{d.day} {MON[d.month-1]} {d.year}, {time_s()}")]
    add("noise_symbol",L,exp(name.title() if name.isupper() else name,n,"EXPENSE","",d))
for _ in range(20):   # not a transaction
    add("negative",[(40,R.choice(["Welcome","Settings","Notifications","Offers for you","Rewards"])),(24,"Scratch cards"),(24,"Invite friends and earn")],"")
R.shuffle(cases)
with open("ocr_cases.tsv","w") as f:
    for i,(cat,L,e) in enumerate(cases):
        lines="||".join(f"{h}@@{t}" if isinstance(L[0],tuple) else f"26@@{t}" for (h,t) in ([x if isinstance(x,tuple) else (26,x) for x in L]))
        f.write(f"{i}\t{cat}\t{lines}\t{e}\n")
from collections import Counter
print(len(cases),Counter(c[0] for c in cases))
