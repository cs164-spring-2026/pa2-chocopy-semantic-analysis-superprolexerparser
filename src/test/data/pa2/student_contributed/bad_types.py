a:int = True
b:bool = 1
s:str = "ok"
nums:[int] = None

class C(object):
    x:int = 0

    def m(self:C, p:int) -> int:
        return p

c:C = None
x:int = 0
y:[int] = None
i:int = 0

nums = [1, True]
c = C()
y = [1, 2]

x = "oops"
y = [None]

x = 1 + [2]
b = not 1
x = -False

if 1:
    x = x

while "loop":
    x = x

for i in 5:
    x = x

x = y[True]
x = s[False]

x = c.m(True)
x = c.nope(1)

x()
