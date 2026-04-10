g_count:int = 0
g_text:str = "ab"
g_items:[int] = None

def helper(a:int, b:int) -> int:
    return a + b

def outer(n:int) -> int:
    total:int = 0

    def step(delta:int) -> int:
        nonlocal total
        total = total + delta
        return total

    i:int = 0
    while i < n:
        step(i)
        i = i + 1
    return total

class A(object):
    x:int = 0

    def inc(self:A, d:int) -> int:
        self.x = self.x + d
        return self.x

    def tag(self:A) -> str:
        return "A"

class B(A):
    y:[int] = None

    def inc(self:B, d:int) -> int:
        self.x = self.x + d
        return self.x

    def tag(self:B) -> str:
        return "B"

    def push(self:B, v:int) -> object:
        self.y = self.y + [v]
        return None

a:A = None
b:B = None
n:int = 0
first:int = 0
choice:int = 0
head:str = ""
v:int = 0
ch:str = ""

g_items = [1, 2, 3]
a = A()
b = B()
a.x = 3
b.x = 4
b.y = [4]

n = helper(a.inc(2), b.inc(1))
first = g_items[0]
choice = n if n > first else first
head = g_text[0]

for v in b.y:
    g_count = g_count + v

for ch in "xy":
    g_text = g_text + ch

b.push(choice)
print(len(b.y))
print(choice)
print(outer(4))
print(head)
