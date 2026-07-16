use std::process::Child;
use std::sync::Mutex;

#[derive(Default)]
pub struct AppState {
    pub python_process: Mutex<Option<Child>>,
}
