"""Tiny gradient-boosted tree model in a plain-text format.

The same file is read by this module (backend) and by KisanEngine.kt (Android),
so both give identical predictions.

Format:
    features <comma separated names>
    init <float>
    lr <float>
    tree <n_nodes>
    feat <ints>      (-1 marks a leaf)
    thr <floats>
    left <ints>
    right <ints>
    val <floats>
    ... repeated per tree
"""
from __future__ import annotations


class TreeModel:
    def __init__(self, features, init, lr, trees):
        self.features, self.init, self.lr, self.trees = features, init, lr, trees

    @classmethod
    def load(cls, path):
        features, init, lr, trees, cur = [], 0.0, 1.0, [], None
        with open(path, encoding="utf-8") as f:
            for line in f:
                parts = line.split()
                if not parts:
                    continue
                key, rest = parts[0], parts[1:]
                if key == "features":
                    features = rest[0].split(",")
                elif key == "init":
                    init = float(rest[0])
                elif key == "lr":
                    lr = float(rest[0])
                elif key == "tree":
                    cur = {}
                    trees.append(cur)
                elif key in ("feat", "left", "right"):
                    cur[key] = [int(x) for x in rest]
                elif key in ("thr", "val"):
                    cur[key] = [float(x) for x in rest]
        return cls(features, init, lr, trees)

    def predict(self, x: dict) -> float:
        row = [float(x[name]) for name in self.features]
        out = self.init
        for t in self.trees:
            i = 0
            while t["feat"][i] >= 0:
                i = t["left"][i] if row[t["feat"][i]] <= t["thr"][i] else t["right"][i]
            out += self.lr * t["val"][i]
        return out


def export_sklearn_gbr(model, features, path):
    """Write a fitted sklearn GradientBoostingRegressor to the text format."""
    init = float(model.init_.constant_[0][0])
    with open(path, "w", encoding="utf-8") as f:
        f.write("features " + ",".join(features) + "\n")
        f.write(f"init {init!r}\nlr {float(model.learning_rate)!r}\n")
        for est in model.estimators_[:, 0]:
            t = est.tree_
            feat = [int(v) if t.children_left[i] != -1 else -1 for i, v in enumerate(t.feature)]
            f.write(f"tree {t.node_count}\n")
            f.write("feat " + " ".join(map(str, feat)) + "\n")
            f.write("thr " + " ".join(repr(float(v)) for v in t.threshold) + "\n")
            f.write("left " + " ".join(str(int(v)) for v in t.children_left) + "\n")
            f.write("right " + " ".join(str(int(v)) for v in t.children_right) + "\n")
            f.write("val " + " ".join(repr(float(v)) for v in t.value[:, 0, 0]) + "\n")
