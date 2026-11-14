"""Generate the bundled sample: one year of synthetic personal transactions (seeded, reproducible).

    python scripts/generate_sample.py > src/main/resources/sample/financial_transactions.csv

Includes a salary, rent, subscriptions on fixed days, everyday spending with realistic spread,
a few deliberate anomalies (a laptop purchase, a double-charged subscription) and a mid-year
salary raise, so the dashboard and the question answering have something real to find.
"""

import calendar
import random
from datetime import date

rng = random.Random(2025)
rows = []


def add(d, description, category, kind, amount, destination):
    rows.append([d, description, category, kind, round(amount, 2), destination])


for month in range(1, 13):
    days = calendar.monthrange(2025, month)[1]
    salary = 5200.00 if month < 7 else 5600.00  # raise in July
    add(date(2025, month, 1), "Monthly Salary", "Income", "CREDIT", salary, "Employer Inc")
    add(date(2025, month, 3), "Payment - Rent", "Housing", "DEBIT", 1450.00, "Landlord LLC")
    add(date(2025, month, 5), "Payment - Electricity", "Utilities", "DEBIT", rng.uniform(70, 140), "City Power")
    add(date(2025, month, 7), "Subscription - Streaming", "Subscriptions", "DEBIT", 15.99, "StreamFlix")
    add(date(2025, month, 12), "Subscription - Music", "Subscriptions", "DEBIT", 10.99, "Tunely")
    add(date(2025, month, 15), "Subscription - Gym", "Health", "DEBIT", 39.00, "FitLab Gym")
    add(date(2025, month, 20), "Payment - Internet", "Utilities", "DEBIT", 59.99, "FiberNet")
    for _ in range(rng.randint(5, 7)):
        add(date(2025, month, rng.randint(1, days)), "Payment - Grocery", "Groceries", "DEBIT",
            rng.uniform(35, 140), rng.choice(["Fresh Market", "GreenGrocer", "Daily Basket"]))
    for _ in range(rng.randint(6, 10)):
        add(date(2025, month, rng.randint(1, days)), "Payment - Coffee", "Food & Dining", "DEBIT",
            rng.uniform(3.5, 6.5), "Bean There Cafe")
    for _ in range(rng.randint(2, 4)):
        add(date(2025, month, rng.randint(1, days)), "Payment - Restaurant", "Food & Dining", "DEBIT",
            rng.uniform(25, 90), rng.choice(["Spice Route", "Pasta Place", "Sushi Bar"]))
    for _ in range(rng.randint(3, 6)):
        add(date(2025, month, rng.randint(1, days)), "Payment - Transit", "Transport", "DEBIT",
            rng.uniform(2.5, 30), rng.choice(["Metro", "City Cabs"]))
    if rng.random() < 0.6:
        add(date(2025, month, rng.randint(1, days)), "Payment - Online Order", "Shopping", "DEBIT",
            rng.uniform(20, 160), rng.choice(["ShopRight", "Bookworm Books"]))
    if month in (3, 9):
        add(date(2025, month, 18), "Freelance Project", "Income", "CREDIT", 900.00 if month == 3 else 1250.00, "Client Studio")

# deliberate anomalies
add(date(2025, 4, 22), "Payment - Laptop", "Electronics", "DEBIT", 1899.00, "TechHub Store")
add(date(2025, 8, 7), "Subscription - Streaming", "Subscriptions", "DEBIT", 15.99, "StreamFlix")  # double charge
add(date(2025, 11, 14), "Payment - Flight", "Travel", "DEBIT", 742.50, "SkyWays Air")
add(date(2025, 12, 20), "Payment - Gifts", "Shopping", "DEBIT", 386.40, "ShopRight")

rows.sort(key=lambda r: (r[0], r[3] != "CREDIT", r[1]))
balance = 2400.00
print("date,description,category,type,amount,balance,destination")
for d, desc, cat, kind, amount, dest in rows:
    balance += amount if kind == "CREDIT" else -amount
    print(f"{d.isoformat()},{desc},{cat},{kind},{amount:.2f},{balance:.2f},{dest}")
