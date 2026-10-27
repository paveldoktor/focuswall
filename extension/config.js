// Заполните значениями из своего проекта Firebase (см. README.md, раздел «Настройка Firebase»).
// Пока поля пустые, расширение работает без входа: списки хранятся только в этом браузере.
export const FIREBASE = {
  apiKey: '',
  projectId: '',
  // OAuth-клиент типа «Web application» из Google Cloud Console.
  // В его Authorized redirect URIs добавьте https://<ID расширения>.chromiumapp.org/
  googleClientId: '',
};

export const firebaseConfigured = () => !!(FIREBASE.apiKey && FIREBASE.projectId && FIREBASE.googleClientId);
