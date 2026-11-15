(function () {
  var buttons = document.querySelectorAll(".copy");
  for (var i = 0; i < buttons.length; i++) {
    buttons[i].addEventListener("click", function () {
      var text = this.getAttribute("data-copy") || "";
      var label = this.getAttribute("data-label") || "Copy";
      var button = this;
      function done(ok) {
        button.textContent = ok ? "Copied" : "Copy failed";
        setTimeout(function () { button.textContent = label; }, 1600);
      }
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function () { done(true); }, function () { done(false); });
      } else {
        var area = document.createElement("textarea");
        area.value = text;
        document.body.appendChild(area);
        area.select();
        var ok = false;
        try { ok = document.execCommand("copy"); } catch (e) { ok = false; }
        document.body.removeChild(area);
        done(ok);
      }
    });
  }
})();
