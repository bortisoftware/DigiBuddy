"use strict";
const button = document.getElementById("language");
let language = "es";
button.addEventListener("click", () => {
  language = language === "es" ? "en" : "es";
  document.documentElement.lang = language;
  document.querySelectorAll("[data-es][data-en]").forEach(element => {
    const parts = element.dataset[language].split("<br>");
    element.replaceChildren();
    parts.forEach((part, index) => {
      if (index) element.append(document.createElement("br"));
      element.append(document.createTextNode(part));
    });
  });
  button.textContent = language === "es" ? "English" : "Español";
  button.setAttribute("aria-label", language === "es" ? "Switch to English" : "Cambiar a Español");
  document.title = language === "es" ? "DigiBuddy — Digimon World en dos pantallas" : "DigiBuddy — Dual-screen Digimon World";
});
