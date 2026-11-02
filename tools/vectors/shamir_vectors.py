# Independent reference for ADR 0020: GF(256), AES polynomial, log/antilog tables (different method from the Kotlin loop).
exp=[0]*512; log=[0]*256; x=1
for i in range(255):
    exp[i]=x; log[x]=i
    # multiply by generator 3: x*2 ^ x
    x2=x<<1
    if x2&0x100: x2^=0x11B
    x=x2^x
for i in range(255,512): exp[i]=exp[i-255]
def mul(a,b): return 0 if a==0 or b==0 else exp[log[a]+log[b]]
def div(a,b): return 0 if a==0 else exp[log[a]-log[b]+255]
assert mul(0x57,0x83)==0xC1
secret=bytes(range(0x10,0x30))           # 32 bytes
k,n=3,5
coef=bytes((i*37+11)%256 for i in range((k-1)*len(secret)))  # fed to Kotlin as the "random" bytes
def share(xv):
    y=bytearray()
    for i,s in enumerate(secret):
        c=[s]+[coef[i*(k-1)+d] for d in range(k-1)]
        acc=0
        for cc in reversed(c): acc=mul(acc,xv)^cc
        y.append(acc)
    return bytes([1,k,xv])+bytes(y)
shares=[share(v) for v in range(1,n+1)]
print("SECRET",secret.hex()); print("COEF",coef.hex())
for s in shares: print("SHARE",s.hex())
# self-check reconstruction from x=2,4,5
def rec(sel):
    out=bytearray()
    for i in range(len(secret)):
        t=0
        for j,(xj,yj) in enumerate(sel):
            num=1;den=1
            for m,(xm,_) in enumerate(sel):
                if m!=j: num=mul(num,xm); den=mul(den,xm^xj)
            t^=mul(yj[i],div(num,den))
        out.append(t)
    return bytes(out)
sel=[(s[2],s[3:]) for s in (shares[1],shares[3],shares[4])]
assert rec(sel)==secret
