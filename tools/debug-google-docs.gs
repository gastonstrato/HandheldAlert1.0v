// Apps Script del documento "Handheld Alert - Debug".
// Recibe los reportes del botón "Enviar debug" de Handheld Alert (POST con
// texto plano) y los agrega al final del documento.
//
// Instalación: en el documento, Extensiones > Apps Script, pegar esto,
// guardar, y en Implementar > Nueva implementación elegir "Aplicación web",
// Ejecutar como: Yo, Quién tiene acceso: Cualquier usuario. La URL que
// termina en /exec es la que usa la app.

const DOC_ID = '1ccZTYskXGOdX1jraBsOwRgyo-j_D-GmLyKdF55ouE-U';

function doPost(e) {
  const texto = (e && e.postData && e.postData.contents) ? e.postData.contents : '';
  if (!texto) {
    return ContentService.createTextOutput('vacio');
  }
  const lock = LockService.getScriptLock();
  lock.waitLock(10000);
  try {
    const body = DocumentApp.openById(DOC_ID).getBody();
    body.appendHorizontalRule();
    body.appendParagraph(texto.substring(0, 50000));
  } finally {
    lock.releaseLock();
  }
  return ContentService.createTextOutput('ok');
}
