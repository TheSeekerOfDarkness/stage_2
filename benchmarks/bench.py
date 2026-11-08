import subprocess, os, time, json, threading, http.client, statistics, sqlite3, glob, sys
import psutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = "java"
EXP = os.path.join(ROOT, "benchmarks", "output")
os.makedirs(EXP, exist_ok=True)
OUT = {}


def kill_services():
    for p in psutil.process_iter(["pid", "cmdline"]):
        c = " ".join(p.info["cmdline"] or [])
        if "-service-1.0.0.jar" in c:
            try:
                p.kill()
            except Exception:
                pass
    time.sleep(1)


def start(name, port, env=None):
    e = dict(os.environ)
    e["PORT"] = str(port)
    e.update(env or {})
    jar = os.path.join(ROOT, name + "-service", "target", name + "-service-1.0.0.jar")
    log = open(os.path.join(EXP, name + "_" + str(port) + ".log"), "w")
    p = subprocess.Popen([JAVA, "-jar", jar], cwd=ROOT, env=e, stdout=log, stderr=log)
    for _ in range(100):
        try:
            c = http.client.HTTPConnection("localhost", port, timeout=2)
            c.request("GET", "/status")
            r = c.getresponse()
            r.read()
            if r.status == 200:
                return p
        except Exception:
            time.sleep(0.2)
    raise RuntimeError("no arranca " + name)


def stop(p):
    try:
        proc = psutil.Process(p.pid)
        for ch in proc.children(recursive=True):
            ch.kill()
        proc.kill()
    except Exception:
        pass
    time.sleep(1)


class Sampler(threading.Thread):
    def __init__(self, procs):
        super().__init__(daemon=True)
        self.procs = {k: psutil.Process(v.pid) for k, v in procs.items()}
        self.stop_flag = False
        self.cpu = {k: [] for k in procs}
        self.rss = {k: [] for k in procs}
        self.host = []
        for pr in self.procs.values():
            pr.cpu_percent(None)
        psutil.cpu_percent(None)

    def run(self):
        while not self.stop_flag:
            time.sleep(0.25)
            for k, pr in self.procs.items():
                try:
                    self.cpu[k].append(pr.cpu_percent(None))
                    self.rss[k].append(pr.memory_info().rss / 1048576)
                except Exception:
                    pass
            self.host.append(psutil.cpu_percent(None))

    def result(self):
        self.stop_flag = True
        time.sleep(0.3)
        res = {}
        for k in self.cpu:
            if self.cpu[k]:
                res[k] = {"cpu_avg_pct_of_core": round(statistics.mean(self.cpu[k]), 1),
                          "cpu_max_pct_of_core": round(max(self.cpu[k]), 1),
                          "rss_avg_mb": round(statistics.mean(self.rss[k]), 1),
                          "rss_max_mb": round(max(self.rss[k]), 1)}
        res["host_cpu_avg_pct"] = round(statistics.mean(self.host), 1) if self.host else None
        res["host_cpu_max_pct"] = round(max(self.host), 1) if self.host else None
        return res


def req(conn, method, path):
    t = time.perf_counter()
    conn.request(method, path)
    r = conn.getresponse()
    body = r.read()
    return r.status, time.perf_counter() - t, body


def book_ids():
    ids = []
    for f in glob.glob(os.path.join(ROOT, "datalake", "*", "*", "*.body.txt")):
        ids.append(int(os.path.basename(f).split(".")[0]))
    return sorted(ids)


def run_clients(n_clients, jobs_fn, ports, method="GET"):
    lats = []
    errors = [0]
    lock = threading.Lock()

    def worker(ci):
        conns = {p: http.client.HTTPConnection("localhost", p, timeout=600) for p in ports}
        mine = []
        err = 0
        for j, path in enumerate(jobs_fn(ci)):
            port = ports[(ci + j) % len(ports)]
            try:
                st, dt, _ = req(conns[port], method, path)
                if st != 200:
                    err += 1
                mine.append(dt)
            except Exception:
                err += 1
        with lock:
            lats.extend(mine)
            errors[0] += err

    ths = [threading.Thread(target=worker, args=(i,)) for i in range(n_clients)]
    t0 = time.perf_counter()
    for t in ths:
        t.start()
    for t in ths:
        t.join()
    return time.perf_counter() - t0, lats, errors[0]


def pct(l, q):
    l = sorted(l)
    return l[min(len(l) - 1, int(q * len(l)))]


def clean_db(db):
    for ext in ("", "-wal", "-shm"):
        if os.path.exists(db + ext):
            os.remove(db + ext)


def e1():
    ids = book_ids()
    out = {"books": len(ids)}
    for label, clients in [("1_cliente", 1), ("4_clientes", 4)]:
        db = os.path.join(EXP, "e1_" + label + ".db")
        clean_db(db)
        idx = start("indexing", 7002, {"AUTO_SYNC": "false", "DATAMART_PATH": db})
        s = Sampler({"indexing": idx})
        s.start()
        chunks = [ids[i::clients] for i in range(clients)]
        elapsed, lats, errs = run_clients(clients, lambda ci: ["/index/" + str(b) for b in chunks[ci]], [7002], "POST")
        res = s.result()
        con = sqlite3.connect(db)
        nb = con.execute("select count(*) from books").fetchone()[0]
        nr = con.execute("select count(*) from inverted_index").fetchone()[0]
        nt = con.execute("select count(distinct term) from inverted_index").fetchone()[0]
        con.close()
        sz = round(sum(os.path.getsize(db + e) for e in ("", "-wal") if os.path.exists(db + e)) / 1048576, 1)
        out[label] = {"elapsed_s": round(elapsed, 2), "books_per_s": round(len(ids) / elapsed, 2), "errors": errs,
                      "lat_mean_ms": round(statistics.mean(lats) * 1000), "lat_p95_ms": round(pct(lats, .95) * 1000),
                      "books_in_db": nb, "index_rows": nr, "distinct_terms": nt, "db_mb": sz, "resources": res}
        if clients == 1:
            s2 = Sampler({"indexing": idx})
            s2.start()
            elapsed2, lats2, errs2 = run_clients(1, lambda ci: ["/index/" + str(b) for b in ids], [7002], "POST")
            s2.result()
            con = sqlite3.connect(db)
            nr2 = con.execute("select count(*) from inverted_index").fetchone()[0]
            nb2 = con.execute("select count(*) from books").fetchone()[0]
            con.close()
            out["reindex_1_cliente"] = {"elapsed_s": round(elapsed2, 2), "books_per_s": round(len(ids) / elapsed2, 2),
                                        "errors": errs2, "books_in_db": nb2, "index_rows_after": nr2, "rows_before": nr}
        stop(idx)
    OUT["e1_indexado"] = out
    print(json.dumps(out, indent=1), flush=True)


QUERIES = ["/search?q=love", "/search?q=whale", "/search?q=love%20sea", "/search?q=king&language=en",
           "/search?q=war&language=en&limit=20&offset=20", "/search?q=love&author=Jane%20Austen",
           "/search?q=night&year=1998", "/search?q=zzzznoexiste", "/search?q=river&limit=10",
           "/search?q=gold&language=en&limit=5"]


def search_load(ports, clients, total=2000):
    per = total // clients
    jobs = lambda ci: [QUERIES[(ci * 7 + j) % len(QUERIES)] for j in range(per)]
    return run_clients(clients, jobs, ports)


def row(el, lats, errs, res):
    return {"requests": len(lats), "elapsed_s": round(el, 2), "req_per_s": round(len(lats) / el),
            "lat_p50_ms": round(pct(lats, .5) * 1000, 1), "lat_p95_ms": round(pct(lats, .95) * 1000, 1),
            "lat_p99_ms": round(pct(lats, .99) * 1000, 1), "errors": errs, "resources": res}


def e2_e3():
    db = os.path.join(EXP, "e1_1_cliente.db")
    out = {}
    s1 = start("search", 7003, {"DATAMART_PATH": db})
    run_clients(2, lambda ci: [QUERIES[j % len(QUERIES)] for j in range(100)], [7003])
    for clients in (1, 4, 8, 16):
        smp = Sampler({"search": s1})
        smp.start()
        el, lats, errs = search_load([7003], clients)
        out["1_replica_" + str(clients) + "_clientes"] = row(el, lats, errs, smp.result())
        print(clients, out["1_replica_" + str(clients) + "_clientes"], flush=True)
    s2 = start("search", 7004, {"DATAMART_PATH": db})
    run_clients(2, lambda ci: [QUERIES[j % len(QUERIES)] for j in range(100)], [7003, 7004])
    for clients in (4, 8, 16):
        smp = Sampler({"search_7003": s1, "search_7004": s2})
        smp.start()
        el, lats, errs = search_load([7003, 7004], clients)
        out["2_replicas_" + str(clients) + "_clientes"] = row(el, lats, errs, smp.result())
        print(clients, out["2_replicas_" + str(clients) + "_clientes"], flush=True)
    stop(s1)
    stop(s2)
    OUT["e2_e3_busqueda"] = out


def e4():
    db = os.path.join(EXP, "e1_1_cliente.db")
    ing = start("ingestion", 7001)
    idx = start("indexing", 7002, {"AUTO_SYNC": "true", "DATAMART_PATH": db})
    out = []
    for b in (1661, 98, 2554):
        c = http.client.HTTPConnection("localhost", 7001, timeout=120)
        t0 = time.perf_counter()
        st, dt, body = req(c, "POST", "/ingest/" + str(b))
        t_ing = time.perf_counter() - t0
        c2 = http.client.HTTPConnection("localhost", 7002, timeout=30)
        t1 = time.perf_counter()
        done = None
        while time.perf_counter() - t1 < 120:
            st2, _, _ = req(c2, "GET", "/index/status/" + str(b))
            if st2 == 200:
                done = time.perf_counter() - t1
                break
            time.sleep(0.25)
        out.append({"book": b, "ingest_http": st, "ingest_s": round(t_ing, 2),
                    "espera_hasta_indexado_s": None if done is None else round(done, 1)})
        print(out[-1], flush=True)
    stop(ing)
    stop(idx)
    OUT["e4_coordinacion"] = {"intervalo_s": 30, "runs": out}


if __name__ == "__main__":
    which = sys.argv[1:]
    kill_services()
    if "e1" in which:
        e1()
    if "e2" in which:
        e2_e3()
    if "e4" in which:
        e4()
    json.dump(OUT, open(os.path.join(EXP, "results_" + "_".join(which) + ".json"), "w"), indent=1)
    print("FIN", flush=True)
