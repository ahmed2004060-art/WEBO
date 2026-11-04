"""Minimal Kotlin lexer: per-line brace/paren depth ignoring strings, comments, chars."""
def scan(src):
    n=len(src); i=0
    depth=0; par=0
    line_start_depth={}; line_start_par={}
    line=1
    line_start_depth[1]=0; line_start_par[1]=0
    in_str=set()
    # stack for string templates: when inside ${ } within string we push mode
    stack=[]  # entries: ('tmpl', saved_depth, kind) kind: 'str' or 'raw'
    def nl():
        nonlocal line
        line+=1
        line_start_depth[line]=depth; line_start_par[line]=par
    def read_string(i, raw):
        # i points just after opening quote(s). returns new i or ('tmpl', i)
        nonlocal line
        while i<n:
            c=src[i]
            if raw:
                if src.startswith('"""',i):
                    j=i+3
                    while j<n and src[j]=='"': j+=1
                    return j,False
                if c=='\n': nl_str(); i+=1; continue
            else:
                if c=='\\': i+=2; continue
                if c=='"': return i+1,False
                if c=='\n': nl_str(); i+=1; continue
            if c=='$' and i+1<n:
                if src[i+1]=='{': return i+2,True
                if src[i+1].isalpha() or src[i+1]=='_':
                    i+=2
                    while i<n and (src[i].isalnum() or src[i]=='_'): i+=1
                    continue
            i+=1
        return i,False
    def nl_str():
        nonlocal line
        line+=1
        line_start_depth[line]=depth; line_start_par[line]=par
        in_str.add(line)
    while i<n:
        c=src[i]
        if c=='\n':
            nl(); i+=1; continue
        if src.startswith('//',i):
            while i<n and src[i]!='\n': i+=1
            continue
        if src.startswith('/*',i):
            lvl=1; i+=2
            while i<n and lvl:
                if src.startswith('/*',i): lvl+=1; i+=2
                elif src.startswith('*/',i): lvl-=1; i+=2
                else:
                    if src[i]=='\n': nl()
                    i+=1
            continue
        if src.startswith('"""',i):
            i,t=read_string(i+3,True)
            if t: stack.append((depth,True)); depth+=1
            continue
        if c=='"':
            i,t=read_string(i+1,False)
            if t: stack.append((depth,False)); depth+=1
            continue
        if c=="'":
            # char literal
            j=i+1
            if j<n and src[j]=='\\':
                j+=2
                while j<n and src[j]!="'": j+=1
            else: j+=1
            if j<n and src[j]=="'": i=j+1; continue
            i+=1; continue
        if c=='{': depth+=1
        elif c=='}':
            depth-=1
            if stack and stack[-1][0]==depth:
                d,raw=stack.pop()
                i,t=read_string(i+1,raw)
                if t: stack.append((depth,raw)); depth+=1
                continue
        elif c in '([': par+=1
        elif c in ')]': par-=1
        i+=1
    return line_start_depth,line_start_par,line,(depth,par,len(stack)),in_str
