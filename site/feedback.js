(function () {
  var p = [
    55, 0, 1, 3, 2, 18, 62, 105, 115, 107, 68, 94, 79, 69, 76, 48, 97,
    55, 61, 58, 2, 15, 21, 24, 105, 73, 119, 122, 127, 76, 64, 29, 89,
    46, 37, 112, 37, 40, 6, 1, 23, 26, 116, 58, 74, 103, 101, 6, 24, 1,
    108, 80, 39, 62, 32, 126, 80, 89, 54, 18, 27, 97, 110, 114, 121, 74
  ];

  function resolveTarget() {
    var out = "";
    for (var i = 0; i < p.length; i++) {
      out += String.fromCharCode(p[i] ^ ((90 + i * 7) & 127));
    }
    return out;
  }

  document.addEventListener("DOMContentLoaded", function () {
    var links = document.querySelectorAll("a[data-feedback]");
    for (var i = 0; i < links.length; i++) {
      links[i].addEventListener("click", function (e) {
        if (!e.isTrusted) return;
        e.preventDefault();
        window.location.href = resolveTarget();
      });
    }
  });
})();
