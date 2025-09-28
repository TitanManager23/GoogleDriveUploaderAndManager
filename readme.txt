*IMPORTANT*

The objective of this application is to have a bot that uploads files to Google Drive. Also, it lets the users put passwords in the files of the Drive, as well as direct codes that skip navigation and passwords. There is a QR code to find the original bot, probably it is not going to be running because the Main class must be running in a computer or server to make it work. To make the bot work, the following steps must be followed:

1. Create a bot in telegram through the BotFather.

2. Get the bot ID, and paste it into src/main/java/bot/FileUploaderBot at line 23, in TokenID.

3. Get the credentials from a Google Drive through the Google Cloud's API of Google Drive.

4. Replace the credentials.json in src/main/resources with the new credentials (change the name to credentials.json if needed).

5. Run the main method in src/main/java/bot/Main.

Note: The admin's default password is 1234567890, change it if needed.