# The library names exactly one class by string: the optional Compose hit tester, looked up with
# Class.forName so that `:feedback` never depends on Compose. That class keeps itself with the rule in
# the `:compose` module's own consumer file, so there is nothing to keep here.
