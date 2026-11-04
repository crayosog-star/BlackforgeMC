from rc import R
r=R(); ok=bad=0; badl=[]
for l in open("out/sample.txt"):
    x,y,z,b=l.split()
    o=r.cmd(f"execute if block {x} {y} {z} minecraft:{b}")
    if "passed" in o: ok+=1
    else: bad+=1; badl.append(l.strip())
print(ok,bad); print("\n".join(badl[:12]))
