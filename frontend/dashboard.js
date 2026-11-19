const activity = document.querySelector("[data-activity]");
const refresh = document.querySelector("[data-refresh]");
const status = document.querySelector("[data-activity-status]");
const list = document.querySelector("[data-activity-list]");

async function loadActivity() {
  refresh.disabled = true;
  status.textContent = "Loading recent activity…";
  try {
    const response = await fetch("/api/v1/activity", { cache: "no-store" });
    if (!response.ok) {
      throw new Error(response.status === 401 ? "Sign in again to view activity." : "Could not load activity.");
    }
    const { items } = await response.json();
    const entries = items.map(({ label, detail, date }) => {
      const item = document.createElement("li");
      const title = document.createElement("strong");
      const description = document.createElement("span");
      const timestamp = document.createElement("small");
      title.textContent = label;
      description.textContent = detail;
      timestamp.textContent = date;
      item.append(title, description, timestamp);
      return item;
    });
    list.replaceChildren(...entries);
    status.textContent = entries.length ? "" : "No recent activity.";
  } catch (error) {
    status.textContent = error.message;
  } finally {
    refresh.disabled = false;
  }
}

if (activity) {
  refresh.addEventListener("click", loadActivity);
  loadActivity();
}
