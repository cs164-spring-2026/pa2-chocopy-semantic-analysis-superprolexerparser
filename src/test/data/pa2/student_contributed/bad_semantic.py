x:int = 1
x:int = 2

class U(object):
    bad:Missing = None

class A(int):
    y:int = 0
    y:int = 1

class B(object):
    z:int = 0

    def f(self:B, p:int) -> int:
        return p

class C(B):
    z:int = 1

    def f(self:C, p:bool) -> int:
        return 0

class D(object):
    def g(x:D, q:int) -> int:
        return q

def outer(a:int) -> int:
    t:int = 0

    def inner(t:int) -> int:
        return t

    return inner(a)

class K(object):
    pass

def bad_shadow(K:int) -> int:
    return K

class B(object):
    other:int = 3
